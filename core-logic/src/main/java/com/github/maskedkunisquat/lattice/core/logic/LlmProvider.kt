package com.github.maskedkunisquat.lattice.core.logic

import kotlinx.coroutines.flow.Flow

/**
 * Contract for an LLM inference backend.
 *
 * Three tiers are implemented:
 * - [NanoProvider]        — Gemini Nano via Google AICore (on-device, API 35+)
 * - [LocalFallbackProvider] — Gemma 3 1B via MediaPipe Tasks GenAI (on-device, all API levels)
 * - [CloudProvider]       — Remote API (off-device, DISABLED by default)
 *
 * The [LlmOrchestrator] selects among these based on hardware availability and
 * the user's explicit consent to cloud routing.
 */
interface LlmProvider {
    /** Stable identifier used in audit logs and UI labels. */
    val id: String

    /** Returns true if this provider can currently handle requests. */
    suspend fun isAvailable(): Boolean

    /**
     * Streams inference results for the given [prompt].
     *
     * Emits [LlmResult.Token] for each generated token, then [LlmResult.Complete]
     * or [LlmResult.Error]. The flow is cold — collection starts inference.
     *
     * @param prompt The (PII-masked) user message — plain text, no chat-format tokens.
     * @param systemInstruction Optional system-level instruction. Providers that support
     *   a native system turn (LiteRT-LM [ConversationConfig], Anthropic `system` field)
     *   use it directly; stub providers may ignore it.
     *
     * IMPORTANT: The prompt must already be PII-masked before reaching any provider
     * that routes data off-device ([CloudProvider]). Local providers run on-device and
     * do not require masking, but callers should mask anyway for consistency.
     */
    fun process(prompt: String, systemInstruction: String? = null): Flow<LlmResult>
}
