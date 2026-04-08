package com.github.maskedkunisquat.lattice.core.logic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * LLM provider that routes requests to a remote cloud API.
 *
 * **DISABLED BY DEFAULT.** The [LlmOrchestrator] will never route to this provider
 * unless `cloudEnabled = true` is explicitly set. When cloud routing does occur,
 * the orchestrator MUST:
 *   1. Flip [PrivacyLevel] to [PrivacyLevel.CloudTransit] (amber UI warning)
 *   2. Write a [com.github.maskedkunisquat.lattice.core.data.model.TransitEvent] to Room
 *
 * PII Requirement: All prompts MUST be masked via [PiiShield] before reaching this
 * provider. The orchestrator enforces this; callers should not bypass it.
 *
 * TODO: Wire a real streaming HTTP client once cloud is enabled:
 *   - Use OkHttp SSE (already in project deps) for streaming token responses
 *   - Inject API key via EncryptedSharedPreferences, never hardcode
 *   - Implement retry with exponential backoff
 */
class CloudProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmProvider {

    override val id = "cloud_claude"

    // Cloud is always "reachable" in principle; network errors surface through LlmResult.Error
    override suspend fun isAvailable(): Boolean = true

    override fun process(prompt: String): Flow<LlmResult> = flow {
        // This stub is intentionally minimal. The real HTTP call goes here.
        // See TODO above for implementation guidance.
        emit(
            LlmResult.Error(
                IllegalStateException(
                    "Cloud provider invoked but HTTP client not configured. " +
                    "Inject an OkHttpClient and API credentials before enabling cloud routing."
                )
            )
        )
    }.flowOn(dispatcher)
}
