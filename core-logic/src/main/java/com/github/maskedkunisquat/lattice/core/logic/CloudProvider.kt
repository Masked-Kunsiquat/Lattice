package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.CloudCredentialStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * LLM provider that routes requests to a remote cloud API.
 *
 * **DISABLED BY DEFAULT.** The [LlmOrchestrator] will never route to this provider
 * unless `cloudEnabled = true` is explicitly set AND an API key is stored in
 * [credentialStore]. When cloud routing does occur, the orchestrator MUST:
 *   1. Flip [PrivacyLevel] to [PrivacyLevel.CloudTransit] (amber UI warning)
 *   2. Write a [com.github.maskedkunisquat.lattice.core.data.model.TransitEvent] to Room
 *
 * PII Requirement: All prompts MUST be masked via [PiiShield] before reaching this
 * provider. The orchestrator enforces this; callers should not bypass it.
 *
 * @param credentialStore Secure API key storage. When non-null, [isAvailable] returns
 *   true only if a key for [id] has been saved. Pass null to skip key gating (tests only).
 *
 * TODO: Wire a real streaming HTTP client:
 *   - Use OkHttp SSE (already in project deps) for streaming token responses
 *   - Read API key via credentialStore.getApiKey(id)
 *   - Implement retry with exponential backoff
 */
class CloudProvider(
    private val credentialStore: CloudCredentialStore? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmProvider {

    override val id = "cloud_claude"

    /**
     * Returns true when an API key is stored for this provider.
     * If no [credentialStore] is injected (test mode), always returns true.
     */
    override suspend fun isAvailable(): Boolean =
        credentialStore?.hasApiKey(id) ?: true

    override fun process(prompt: String): Flow<LlmResult> = flow {
        // Stub until OkHttp client is wired. Read key via credentialStore.getApiKey(id).
        emit(
            LlmResult.Error(
                IllegalStateException(
                    "Cloud provider invoked but HTTP client not configured. " +
                    "Wire an OkHttpClient and read the API key from CloudCredentialStore."
                )
            )
        )
    }.flowOn(dispatcher)
}
