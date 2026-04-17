package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.FileNotFoundException
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ModelLoadState { IDLE, COPYING_MODEL, LOADING_SESSION, READY, ERROR }

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

    /**
     * Triggers the [ModelDownloadWorker] to fetch the appropriate model tier.
     * UI should observe [getDownloadWorkInfo] to track progress.
     */
    fun downloadModel() {
        val modelAsset = resolveModelName()
        val workManager = WorkManager.getInstance(context)
        
        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_ASSET to modelAsset,
                ModelDownloadWorker.KEY_URL to "$HF_BASE_URL/$modelAsset"
            ))
            .addTag(ModelDownloadWorker.UNIQUE_WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP, // Don't restart if already running
            downloadRequest
        )
    }

    /** Returns a Flow of WorkInfo to track download progress in the UI. */
    fun getDownloadWorkInfo(): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
            .map { it.firstOrNull() }
    }

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
                val modelAsset = resolveModelName()
                Log.i(TAG, "Selected model tier: $modelAsset (board=${Build.BOARD}, hardware=${Build.HARDWARE})")
                
                _modelLoadState.value = ModelLoadState.COPYING_MODEL
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
     * Returns the model asset filename for the current device based on [Build.BOARD]
     * and [Build.HARDWARE]. Recognizes "sun" and "8750" for Elite tier, and "8650"
     * for Ultra tier.
     */
    private fun resolveModelName(): String {
        val board = Build.BOARD.lowercase(java.util.Locale.ROOT)
        val hardware = Build.HARDWARE.lowercase(java.util.Locale.ROOT)
        
        return when {
            // "sun" and "kailua" are codenames for SM8750 (S25 Series / Snapdragon 8 Elite)
            board == "sun" || board == "kailua" || (hardware == "qcom" && board.contains("8750")) -> MODEL_ELITE
            // "kalama" and "8650" refer to Snapdragon 8 Gen 3 (S24 Series)
            board == "kalama" || board.contains("8650") -> MODEL_ULTRA
            else -> MODEL_UNIVERSAL
        }
    }

    // ── Model copy ────────────────────────────────────────────────────────────

    /**
     * Copies [modelAsset] from assets to [Context.filesDir] if not already present.
     * Uses a .tmp intermediary and atomic rename so a partial copy is never visible
     * as a complete file.
     *
     * Note: If the file is already in [Context.filesDir] (e.g. from a side-load or
     * previous run), this method returns early.
     */
    private fun copyModelIfNeeded(modelAsset: String) {
        val dest = File(context.filesDir, modelAsset)
        val tmp  = File(context.filesDir, "$modelAsset.tmp")
        if (dest.exists()) {
            Log.d(TAG, "Model $modelAsset already exists in internal storage; skipping copy.")
            return
        }

        // The downloader (Gradle/Settings) might have already placed it here.
        // We only attempt to copy from Assets if it's missing from internal storage.
        try {
            context.assets.open(modelAsset).use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(2 * 1024 * 1024)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) {
                        dst.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                throw IOException("Failed to rename $tmp to $dest")
            }
        } catch (e: FileNotFoundException) {
            // If it's not in assets AND not in dest, then it's actually missing.
            Log.e(TAG, "Model $modelAsset not found in assets. It must be downloaded or side-loaded.")
            throw IOException("Model asset missing: $modelAsset. Run ./gradlew downloadModels or download in Settings.")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"
        private const val HF_BASE_URL = "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main"

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
