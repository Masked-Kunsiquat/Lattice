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
 * 2. [localFallbackProvider] — Qwen-1.5B via ONNX (on-device, all API levels)
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
 * [process]. The orchestrator validates this for cloud-bound prompts in Task 3.2.
 *
 * @param cloudEnabled Gates cloud routing. False by default — users must explicitly opt in.
 */
class LlmOrchestrator(
    private val nanoProvider: LlmProvider,
    private val localFallbackProvider: LlmProvider,
    private val cloudProvider: LlmProvider,
    private val transitEventDao: TransitEventDao,
    val cloudEnabled: Boolean = false
) {
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
        applyPrivacyState(provider, operationType)
        emitAll(provider.process(prompt))
    }

    private suspend fun selectProvider(): LlmProvider {
        if (nanoProvider.isAvailable()) return nanoProvider
        if (localFallbackProvider.isAvailable()) return localFallbackProvider
        if (cloudEnabled) return cloudProvider
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
