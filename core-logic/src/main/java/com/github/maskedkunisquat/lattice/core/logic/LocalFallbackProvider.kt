package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.os.Build
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
 * ## Hardware tiers
 * Three variants are available. The asset is selected at [initialize] time based on
 * [Build.BOARD]:
 *
 * | Tier | File | Target SoC | Backend |
 * |---|---|---|---|
 * | Elite | `gemma3-1b-it-elite.litertlm` | SM8750 (S25 Ultra) | Adreno 830 AOT kernels |
 * | Ultra | `gemma3-1b-it-ultra.litertlm` | SM8650 (S24 Ultra) | Adreno 750 AOT kernels |
 * | Universal | `gemma3-1b-it-universal.task` | Any ARM64 | JIT / OpenCL fallback |
 *
 * Run `./gradlew downloadModels` to fetch the correct variant for the connected device.
 * Override with `-PdownloadTier=elite|ultra|universal`.
 *
 * ## Inference
 * Uses MediaPipe's [LlmInference] session with a 1,280-token KV-cache context (`ekv1280`).
 *
 * ## Model lifecycle
 * The selected file is copied from assets to [Context.filesDir] on first [initialize]
 * call. Subsequent launches skip the copy if the file is already present.
 */
class LocalFallbackProvider(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmProvider {

    override val id = "gemma3_1b_mediapipe"

    @Volatile private var llmInference: LlmInference? = null
    
    // Bridging callbacks for the singleton LlmInference session. 
    // Set per-request in process().
    private var currentPartialResultListener: ((String, Boolean) -> Unit)? = null
    private var currentErrorListener: ((Throwable) -> Unit)? = null

    @Volatile private var initAttempted = false
    @Volatile private var initFailureReason: String? = null
    private val initLock = Any()

    private val _modelLoadState = MutableStateFlow(ModelLoadState.IDLE)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    /** 0.0–1.0 progress during [ModelLoadState.COPYING_SHARDS]; 0 otherwise. */
    private val _copyProgress = MutableStateFlow(0f)
    val copyProgress: StateFlow<Float> = _copyProgress.asStateFlow()

    /**
     * Selects the hardware tier, copies the model file to internal storage (if needed),
     * then opens a [LlmInference] session via MediaPipe. Safe to call multiple times;
     * subsequent calls are no-ops. Silent on failure — [isAvailable] returns false and
     * the orchestrator handles the fallback.
     */
    fun initialize() {
        if (initAttempted) return
        synchronized(initLock) {
            if (initAttempted) return
            initAttempted = true
            try {
                val modelAsset = selectModelAsset()
                Log.i(TAG, "Selected model tier: $modelAsset (board=${Build.BOARD})")
                
                _modelLoadState.value = ModelLoadState.COPYING_SHARDS
                copyModelIfNeeded(modelAsset)
                
                _modelLoadState.value = ModelLoadState.LOADING_SESSION
                val modelPath = File(context.filesDir, modelAsset).absolutePath
                
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(MAX_TOKENS)
                    .setResultListener { partial, done -> 
                        currentPartialResultListener?.invoke(partial, done)
                    }
                    .setErrorListener { error ->
                        currentErrorListener?.invoke(error)
                    }
                    .build()
                
                llmInference = LlmInference.createFromOptions(context, options)
                _modelLoadState.value = ModelLoadState.READY
                Log.i(TAG, "MediaPipe LlmInference session ready — model=$modelAsset")
            } catch (e: Exception) {
                _copyProgress.value = 0f
                _modelLoadState.value = ModelLoadState.ERROR
                initFailureReason = "${e::class.simpleName}: ${e.message}"
                Log.w(TAG, "LocalFallbackProvider init failed", e)
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
            val reason = initFailureReason ?: "Call LocalFallbackProvider.initialize() first."
            send(LlmResult.Error(IllegalStateException("Model not loaded. $reason")))
            close()
            return@callbackFlow
        }

        // Setup bridging for this specific flow collection
        currentPartialResultListener = { partial, done ->
            if (partial.isNotEmpty()) {
                trySend(LlmResult.Token(partial))
            }
            if (done) {
                trySend(LlmResult.Complete)
                close()
            }
        }
        
        currentErrorListener = { error ->
            close(error)
        }

        try {
            inference.generateResponseAsync(prompt)
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            currentPartialResultListener = null
            currentErrorListener = null
        }
    }.flowOn(dispatcher)

    // ── Tier selection ────────────────────────────────────────────────────────

    /**
     * Returns the model asset filename for the current device based on [Build.BOARD]:
     * - `kailua` → [MODEL_ELITE]  (SM8750 — Snapdragon 8 Elite)
     * - `kalama` → [MODEL_ULTRA]  (SM8650 — Snapdragon 8 Gen 3)
     * - Anything else → [MODEL_UNIVERSAL]
     */
    private fun selectModelAsset(): String = when (Build.BOARD.lowercase()) {
        "kailua" -> MODEL_ELITE
        "kalama" -> MODEL_ULTRA
        else     -> MODEL_UNIVERSAL
    }

    // ── Model copy ────────────────────────────────────────────────────────────

    /**
     * Copies [modelAsset] from assets to [Context.filesDir] if not already present.
     * Uses a .tmp intermediary and atomic rename so a partial copy is never visible
     * as a complete file.
     */
    private fun copyModelIfNeeded(modelAsset: String) {
        val dest = File(context.filesDir, modelAsset)
        val tmp  = File(context.filesDir, "$modelAsset.tmp")
        if (dest.exists()) {
            _copyProgress.value = 1f
            return
        }
        try {
            context.assets.open(modelAsset).use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(2 * 1024 * 1024)
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

        // Hardware-optimised tiers — selected by selectModelAsset() at runtime.
        private const val MODEL_ELITE     = "gemma3-1b-it-elite.litertlm"
        private const val MODEL_ULTRA     = "gemma3-1b-it-ultra.litertlm"
        private const val MODEL_UNIVERSAL = "gemma3-1b-it-universal.task"

        // Matches the model's ekv1280 KV-cache context window.
        private const val MAX_TOKENS = 1280

        // Approximate compressed model size — used for copy-progress estimate.
        private const val APPROX_MODEL_BYTES = 800_000_000L
    }
}
