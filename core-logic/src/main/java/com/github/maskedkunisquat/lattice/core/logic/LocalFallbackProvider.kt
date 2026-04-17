package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

enum class ModelLoadState { IDLE, COPYING_SHARDS, LOADING_SESSION, READY, ERROR }

/**
 * LLM provider backed by the locally-bundled Gemma 3 1B Instruct LiteRT model.
 *
 * This is the primary fallback when Gemini Nano (AICore) is unavailable. The model
 * runs entirely on-device via MediaPipe Tasks GenAI — no data leaves the device.
 *
 * ## Asset setup
 * Place the following file in app/src/main/assets/:
 *   gemma3_1b_it.task   — Gemma 3 1B Instruct LiteRT task bundle (INT4)
 *
 * Run `./gradlew downloadModels` to fetch from HuggingFace.
 *
 * ## Inference
 * Uses MediaPipe's [LlmInference] session. On Adreno 700-series GPUs (e.g. the S25
 * Ultra's Snapdragon 8 Elite) this runs at 35–50 tok/s vs ~8 tok/s for the prior
 * Llama 3.2-3B on CPU+NNAPI. Backend selection is automatic — GPU when available,
 * CPU fallback otherwise.
 *
 * ## Model lifecycle
 * The .task file is copied from assets to internal storage on first [initialize] call.
 * Subsequent launches skip the copy if the file is already present. MediaPipe loads
 * the session directly from the filesystem path.
 */
class LocalFallbackProvider(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmProvider {

    override val id = "gemma3_1b_mediapipe"

    @Volatile private var llmInference: LlmInference? = null

    @Volatile private var initAttempted = false
    @Volatile private var initFailureReason: String? = null
    private val initLock = Any()

    private val _modelLoadState = MutableStateFlow(ModelLoadState.IDLE)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    /** 0.0–1.0 progress during [ModelLoadState.COPYING_SHARDS]; 0 otherwise. */
    private val _copyProgress = MutableStateFlow(0f)
    val copyProgress: StateFlow<Float> = _copyProgress.asStateFlow()

    /**
     * Copies the model file from assets to internal storage (if needed) then opens
     * a [LlmInference] session via MediaPipe. Safe to call multiple times; subsequent
     * calls are no-ops. Silent on failure — [isAvailable] returns false and the
     * orchestrator handles the fallback.
     */
    fun initialize() {
        if (initAttempted) return
        synchronized(initLock) {
            if (initAttempted) return
            initAttempted = true
            try {
                _modelLoadState.value = ModelLoadState.COPYING_SHARDS
                copyModelIfNeeded()
                _modelLoadState.value = ModelLoadState.LOADING_SESSION
                val modelPath = File(context.filesDir, MODEL_ASSET).absolutePath
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_NEW_TOKENS)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
                _modelLoadState.value = ModelLoadState.READY
                Log.i(TAG, "MediaPipe LlmInference session ready — model=$MODEL_ASSET")
            } catch (e: Exception) {
                _copyProgress.value = 0f
                _modelLoadState.value = ModelLoadState.ERROR
                initFailureReason = "${e::class.simpleName}: ${e.message}"
                Log.w(TAG, "LocalFallbackProvider init failed — provider unavailable", e)
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (!initAttempted) withContext(dispatcher) { initialize() }
        return llmInference != null
    }

    /**
     * Streams inference results for [prompt] via the Gemma 3 1B MediaPipe session.
     *
     * Emits [LlmResult.Token] for each text chunk (streaming), then [LlmResult.Complete]
     * on finish or [LlmResult.Error] on failure.
     */
    override fun process(prompt: String): Flow<LlmResult> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            val reason = initFailureReason
                ?.let { " Init failed with: $it" }
                ?: " Ensure $MODEL_ASSET is in app/src/main/assets/" +
                   " and call LocalFallbackProvider.initialize() at app startup."
            send(LlmResult.Error(IllegalStateException("Gemma 3 1B model not loaded.$reason")))
            close()
            return@callbackFlow
        }

        try {
            inference.generateAsync(prompt) { partialResult, done ->
                if (!partialResult.isNullOrEmpty()) {
                    trySend(LlmResult.Token(partialResult))
                }
                if (done) {
                    trySend(LlmResult.Complete)
                    close()
                }
            }
        } catch (e: Exception) {
            close(e)
        }

        awaitClose { /* MediaPipe does not expose a per-request cancellation API */ }
    }.flowOn(dispatcher)

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Copies [MODEL_ASSET] from assets to [Context.filesDir] if not already present.
     * Uses a .tmp intermediary and atomic rename so a partial copy is never visible
     * as a complete file.
     */
    private fun copyModelIfNeeded() {
        val dest = File(context.filesDir, MODEL_ASSET)
        val tmp  = File(context.filesDir, "$MODEL_ASSET.tmp")
        if (tmp.exists()) tmp.delete()
        if (dest.exists()) {
            _copyProgress.value = 1f
            return
        }
        try {
            context.assets.open(MODEL_ASSET).use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(2 * 1024 * 1024) // 2 MB chunks
                    var copied = 0L
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                        copied += n
                        _copyProgress.value = (copied.toFloat() / APPROX_MODEL_BYTES).coerceIn(0f, 1f)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                throw IOException("Failed to rename $tmp to $dest")
            }
            _copyProgress.value = 1f
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"
        private const val MODEL_ASSET = "gemma3_1b_it.task"
        private const val MAX_NEW_TOKENS = 512
        private const val APPROX_MODEL_BYTES = 1_500_000_000L
    }
}
