package com.github.maskedkunisquat.lattice.core.logic

import android.util.Log
import com.github.maskedkunisquat.lattice.core.data.CloudCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LLM provider that routes requests to the Anthropic Messages API with SSE streaming.
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
 */
class CloudProvider(
    private val credentialStore: CloudCredentialStore? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmProvider {

    override val id = "cloud_claude"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns true when an API key is stored for this provider.
     * If no [credentialStore] is injected (test mode), always returns true.
     */
    override suspend fun isAvailable(): Boolean =
        credentialStore?.hasApiKey(id) ?: true

    override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flow {
        val apiKey = credentialStore?.getApiKey(id)
        if (apiKey == null) {
            emit(LlmResult.Error(IllegalStateException("No API key stored for provider '$id'")))
            return@flow
        }

        var attempt = 0
        while (true) {
            val result = runCatching { streamOnce(prompt, systemInstruction, apiKey) { emit(it) } }
            val error = result.exceptionOrNull()

            if (error is CancellationException) throw error

            if (error != null) {
                val retryable = error is RetryableCloudException
                if (retryable && attempt < MAX_RETRIES) {
                    val backoffMs = BACKOFF_BASE_MS shl attempt   // 1 s, 2 s, 4 s
                    val jitter = kotlin.random.Random.nextLong(backoffMs / 2)
                    val jitteredMs = backoffMs + jitter
                    Log.w(TAG, "Cloud request failed (attempt ${attempt + 1}), retrying in ${jitteredMs}ms", error)
                    delay(jitteredMs)
                    attempt++
                    continue
                }
                emit(LlmResult.Error(error))
                return@flow
            }

            return@flow
        }
    }.flowOn(dispatcher)

    /**
     * Executes a single streaming request to the Anthropic Messages API, emitting each
     * [LlmResult] token to [onResult] as it is parsed from the SSE stream.
     *
     * Throws [RetryableCloudException] for transient HTTP errors (429, 5xx),
     * or [IOException] for network failures.
     */
    private suspend fun streamOnce(
        prompt: String,
        systemInstruction: String?,
        apiKey: String,
        onResult: suspend (LlmResult) -> Unit,
    ) {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("stream", true)
            .apply { if (!systemInstruction.isNullOrBlank()) put("system", systemInstruction) }
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", prompt))
            })
            .toString()

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeRequest(client.newCall(request))
        response.use { resp ->
            if (!resp.isSuccessful) {
                val code = resp.code
                val msg = runCatching { resp.body?.string() }.getOrNull() ?: ""
                if (code == 429 || code in 500..599) {
                    throw RetryableCloudException("HTTP $code: $msg")
                }
                throw IOException("Cloud API error HTTP $code: $msg")
            }

            val reader = resp.body?.charStream()?.buffered()
                ?: throw IOException("Empty response body from cloud API")

            reader.use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val raw = line!!
                    if (raw.isBlank() || raw.startsWith("event:")) continue
                    if (!raw.startsWith("data:")) continue

                    val data = raw.removePrefix("data:").trim()
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    when (json.optString("type")) {
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            val text = delta?.optString("text") ?: continue
                            if (text.isNotEmpty()) onResult(LlmResult.Token(text))
                        }
                        "message_stop" -> break
                        "error" -> {
                            val errorObj = json.optJSONObject("error")
                            val msg = errorObj?.optString("message") ?: "Unknown cloud error"
                            onResult(LlmResult.Error(IOException("Cloud API error: $msg")))
                            return@use
                        }
                    }
                }
            }

            onResult(LlmResult.Complete)
        }
    }

    /**
     * Wraps an OkHttp [Call] as a suspendable operation.
     * Cancels the underlying HTTP call when the coroutine is cancelled.
     */
    private suspend fun executeRequest(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        response.close()
                    }
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }

    /** Signals that the request should be retried (429 or 5xx status). */
    private class RetryableCloudException(message: String) : IOException(message)

    companion object {
        private const val TAG = "CloudProvider"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_TOKENS = 2048
        private const val MAX_RETRIES = 3
        private const val BACKOFF_BASE_MS = 1_000L
    }
}
