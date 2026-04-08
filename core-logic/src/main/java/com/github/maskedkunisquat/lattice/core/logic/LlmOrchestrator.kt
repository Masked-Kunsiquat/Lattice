package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Privacy Switchboard — selects the best available LLM provider and enforces the
 * sovereignty gate when data would leave the device.
 *
 * ## Routing priority (local-first)
 * 1. [nanoProvider]          — Gemini Nano via AICore (API 35+, on-device)
 * 2. [localFallbackProvider] — Llama-3.2-3B via ONNX (on-device, all API levels)
 * 3. [cloudProvider]         — Remote API (**only** when [cloudEnabled] = true)
 *
 * ## Sovereignty Gate
 * When a request is routed to [cloudProvider]:
 * - [privacyState] flips to [PrivacyLevel.CloudTransit] (UI shows amber border)
 * - A [TransitEvent] is written to Room (local audit trail — *what* was sent is never logged)
 * When a local provider is used, [privacyState] resets to [PrivacyLevel.LocalOnly].
 *
 * ## PII Contract
 * Callers are responsible for masking PII via [PiiShield] before passing a prompt to
 * [process]. The orchestrator enforces this for cloud-bound prompts via [piiDetector]:
 * if [piiDetector] returns true (raw PII detected), the request is rejected with
 * [LlmResult.Error] before any data leaves the device.
 *
 * @param cloudEnabled Gates cloud routing. False by default — users must explicitly opt in.
 * @param piiDetector Required when [cloudEnabled] is true. Returns true if the prompt contains
 *   unmasked PII; when true, cloud dispatch is blocked and [LlmResult.Error] is emitted before
 *   data leaves the device. Pass `{ false }` to explicitly opt out of PII checking (only safe
 *   when the caller guarantees all prompts are already masked via [PiiShield]).
 *   Implementations should not throw — if the detector throws, the orchestrator treats the
 *   prompt as containing PII (fail-safe: block the request rather than risk leaking data).
 *   Null is only valid when [cloudEnabled] is false; constructing with `cloudEnabled=true` and
 *   `piiDetector=null` will throw [IllegalArgumentException].
 */
class LlmOrchestrator(
    private val nanoProvider: LlmProvider,
    private val localFallbackProvider: LlmProvider,
    private val cloudProvider: LlmProvider? = null,
    private val transitEventDao: TransitEventDao,
    val cloudEnabled: Boolean = false,
    private val piiDetector: ((String) -> Boolean)? = null
) {
    init {
        require(!cloudEnabled || cloudProvider != null) {
            "cloudProvider must be supplied when cloudEnabled=true."
        }
        require(!cloudEnabled || piiDetector != null) {
            "piiDetector must be provided when cloudEnabled=true. " +
            "Pass { false } to explicitly opt out of PII checking only if prompts are pre-masked."
        }
    }
    private val _privacyState = MutableStateFlow<PrivacyLevel>(PrivacyLevel.LocalOnly)

    /** Observed by the UI to render the blue / amber privacy border. */
    val privacyState: StateFlow<PrivacyLevel> = _privacyState.asStateFlow()

    /**
     * Routes [prompt] to the best available provider and streams the result.
     *
     * @param prompt     The (PII-masked) text to process.
     * @param operationType Label for the audit log (e.g. "reframe", "summarize").
     */
    fun process(
        prompt: String,
        operationType: String = "reframe"
    ): Flow<LlmResult> = flow {
        val provider = selectProvider()
        val piiDetected = provider === cloudProvider && runCatching { piiDetector?.invoke(prompt) ?: false }.getOrDefault(true)
        if (piiDetected) {
            emit(
                LlmResult.Error(
                    SecurityException(
                        "Cloud dispatch blocked: raw PII detected in prompt. " +
                        "Mask all personal data via PiiShield before routing to cloud."
                    )
                )
            )
            return@flow
        }
        applyPrivacyState(provider, operationType)
        emitAll(provider.process(prompt))
    }

    private suspend fun selectProvider(): LlmProvider {
        if (nanoProvider.isAvailable()) return nanoProvider
        if (localFallbackProvider.isAvailable()) return localFallbackProvider
        // cloudProvider is non-null here: init block enforces this when cloudEnabled=true.
        if (cloudEnabled && cloudProvider != null) return cloudProvider
        // No provider available and cloud not enabled — return localFallback which will
        // emit LlmResult.Error explaining the model is not bundled.
        return localFallbackProvider
    }

    private suspend fun applyPrivacyState(provider: LlmProvider, operationType: String) {
        if (provider === cloudProvider) {
            val timestamp = System.currentTimeMillis()

            // Sovereignty gate: flip the amber warning state
            _privacyState.value = PrivacyLevel.CloudTransit(
                providerName = cloudProvider.id,
                sinceTimestamp = timestamp
            )

            // Write the local audit trail entry — timestamp + provider, never the prompt
            transitEventDao.insertEvent(
                TransitEvent(
                    id = UUID.randomUUID(),
                    timestamp = timestamp,
                    providerName = cloudProvider.id,
                    operationType = operationType
                )
            )
        } else {
            _privacyState.value = PrivacyLevel.LocalOnly
        }
    }
}
