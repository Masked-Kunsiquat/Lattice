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
 * 2. [localFallbackProvider] — Gemma 3 1B via LiteRT-LM (on-device, all API levels)
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
 * @param cloudEnabled Suspend lambda returning true when cloud routing is permitted.
 *   Defaults to `{ false }`. Wire to [SettingsRepository.settings] so the value tracks
 *   the user's DataStore preference across process restarts.
 * @param piiDetector Required when [cloudProvider] is supplied. Returns true if the prompt
 *   contains unmasked PII; when true, cloud dispatch is blocked and [LlmResult.Error] is
 *   emitted before data leaves the device. Pass `{ false }` to explicitly opt out of PII
 *   checking (only safe when the caller guarantees all prompts are already masked via
 *   [PiiShield]). Implementations should not throw — if the detector throws, the orchestrator
 *   treats the prompt as containing PII (fail-safe). Must be non-null whenever [cloudProvider]
 *   is non-null; constructing with a non-null [cloudProvider] and null [piiDetector] throws
 *   [IllegalArgumentException].
 */
class LlmOrchestrator(
    private val nanoProvider: LlmProvider,
    private val localFallbackProvider: LlmProvider,
    private val cloudProvider: LlmProvider? = null,
    private val transitEventDao: TransitEventDao,
    private val cloudEnabled: suspend () -> Boolean = { false },
    private val piiDetector: ((String) -> Boolean)? = null
) {
    init {
        // piiDetector is required whenever a cloud provider is wired in, because
        // cloudEnabled can flip to true at runtime — we need PII checking ready.
        require(cloudProvider == null || piiDetector != null) {
            "piiDetector must be provided when cloudProvider is supplied. " +
            "Pass { false } to explicitly opt out of PII checking only if prompts are pre-masked."
        }
    }
    private val _privacyState = MutableStateFlow<PrivacyLevel>(PrivacyLevel.LocalOnly)

    /** Observed by the UI to render the blue / amber privacy border. */
    val privacyState: StateFlow<PrivacyLevel> = _privacyState.asStateFlow()

    /**
     * Routes [prompt] to the best available provider and streams the result.
     *
     * @param prompt            The (PII-masked) user message — plain text, no chat tokens.
     * @param operationType     Label for the audit log (e.g. "reframe", "summarize").
     * @param systemInstruction Optional system-level instruction forwarded to the provider.
     *   **Must be developer-authored** — all call sites pass string literals defined in
     *   [ReframingLoop] ([ReframingLoop.AFFECTIVE_SYSTEM] etc.). The PII gate only checks
     *   [prompt]; [systemInstruction] is assumed to contain no user-derived content.
     */
    fun process(
        prompt: String,
        operationType: String = "reframe",
        systemInstruction: String? = null,
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
        emitAll(provider.process(prompt, systemInstruction))
    }

    private suspend fun selectProvider(): LlmProvider {
        if (nanoProvider.isAvailable()) return nanoProvider
        if (localFallbackProvider.isAvailable()) return localFallbackProvider
        if (cloudEnabled() && cloudProvider != null) return cloudProvider
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
