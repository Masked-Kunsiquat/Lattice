package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.io.IOException
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class ModelLoadState { IDLE, COPYING_MODEL, LOADING_SESSION, READY, ERROR }

/**
 * LLM provider backed by a locally-stored Gemma 3 1B Instruct LiteRT-LM model.
 *
 * This is the primary fallback when Gemini Nano (AICore) is unavailable. Inference
 * runs entirely on-device via the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
 * runtime — no data leaves the device.
 *
 * ## Model file
 * A single file ([MODEL_FILE]) covers all devices. It is a standard LiteRT-LM
 * int4-quantised model that supports both CPU and GPU execution via OpenCL JIT.
 * Hosted at `masked-kunsiquat/gemma-3-1b-it-litert` on HuggingFace (public, no auth).
 * Source: `gemma3-1b-it-int4.litertlm` from `litert-community/Gemma3-1B-IT`.
 *
 * Note: the hardware-specific `_sm8750` / `_sm8650` files from litert-community are
 * **NPU/QNN (Qualcomm Hexagon) compiled** — they contain no CPU or Adreno GPU tensors
 * and cannot be loaded via [Backend.GPU] or [Backend.CPU]. Do not use them here.
 *
 * ## Backend selection
 * [Backend.GPU] (OpenCL JIT) is attempted first on Qualcomm Snapdragon devices.
 * If GPU init fails for any reason, [Backend.CPU] is used automatically.
 *
 * ## Model lifecycle
 * The [Engine] is a heavy singleton created in [initialize] and kept alive for the
 * lifetime of the provider. Each [process] call creates a fresh Conversation (no
 * accumulated turn history — [ReframingLoop] manages all context externally).
 */
class LocalFallbackProvider(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmProvider {

    override val id = "gemma3_1b_litertlm"

    @Volatile private var engine: Engine? = null

    @Volatile private var initAttempted = false
    @Volatile private var initFailureReason: String? = null
    // Set to true the first time a GPU inference call fails due to missing OpenCL.
    // Subsequent initialize() calls will skip GPU entirely.
    @Volatile private var openClFailed = false
    private val initLock = Any()

    private val _modelLoadState = MutableStateFlow(ModelLoadState.IDLE)
    val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    /**
     * Triggers the [ModelDownloadWorker] to fetch [MODEL_FILE].
     * UI should observe [getDownloadWorkInfo] to track progress.
     */
    fun downloadModel() {
        val workManager = WorkManager.getInstance(context)

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_ASSET to MODEL_FILE,
                ModelDownloadWorker.KEY_URL to "$HF_BASE_URL/$MODEL_FILE"
            ))
            .addTag(ModelDownloadWorker.UNIQUE_WORK_NAME)
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )
    }

    /** Returns a Flow of [WorkInfo] to track download progress in the UI. */
    fun getDownloadWorkInfo(): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.UNIQUE_WORK_NAME)
            .map { it.firstOrNull() }
    }

    /**
     * Creates the [Engine] and marks the provider ready.
     * Safe to call multiple times — no-op once engine is loaded.
     * Automatically resets after a failure so callers can retry (e.g. after
     * [ModelDownloadWorker] replaces a corrupt or incompatible file).
     *
     * Tries [Backend.GPU] first on Snapdragon devices; falls back to [Backend.CPU]
     * if GPU init fails (e.g. device lacks OpenCL support).
     */
    fun initialize() {
        if (engine != null) return
        synchronized(initLock) {
            if (engine != null) return
            initAttempted = true

            val modelFile = File(context.filesDir, MODEL_FILE)
            Log.i(TAG, "Initialising engine — file=${MODEL_FILE} board=${Build.BOARD} hardware=${Build.HARDWARE}")

            if (!modelFile.exists()) {
                _modelLoadState.value = ModelLoadState.ERROR
                initFailureReason = "Model not found: $MODEL_FILE. Download it from Settings."
                return
            }

            _modelLoadState.value = ModelLoadState.LOADING_SESSION

            val backendsToTry: List<Backend> =
                if (!openClFailed && isQualcommDevice()) listOf(Backend.GPU(), Backend.CPU())
                else listOf(Backend.CPU())

            var lastException: Exception? = null
            for (backend in backendsToTry) {
                try {
                    val eng = Engine(EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = context.cacheDir.path,
                    ))
                    eng.initialize()
                    engine = eng
                    _modelLoadState.value = ModelLoadState.READY
                    Log.i(TAG, "LiteRT-LM engine ready — backend=${backend::class.simpleName}")
                    return
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Engine init failed with backend ${backend::class.simpleName} — ${e.message}")
                }
            }

            // All backends exhausted.
            val e = lastException!!
            _modelLoadState.value = ModelLoadState.ERROR
            initFailureReason = "${e::class.simpleName}: ${e.message}"
            Log.w(TAG, "LocalFallbackProvider init failed", e)
            // Delete so the next "Download" tap fetches a fresh copy.
            try {
                if (modelFile.exists() && modelFile.delete()) {
                    Log.w(TAG, "Deleted incompatible model file: ${modelFile.name} — re-download required")
                }
            } catch (_: Exception) { }
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (!initAttempted) withContext(dispatcher) { initialize() }
        return engine != null
    }

    /**
     * Streams inference results for [prompt] via a fresh LiteRT-LM Conversation.
     *
     * A new Conversation is created per call — [ReframingLoop] manages all multi-turn
     * context externally and passes a self-contained prompt each time.
     *
     * Emits [LlmResult.Token] for each streamed chunk, then [LlmResult.Complete] on
     * finish, or [LlmResult.Error] on failure.
     *
     * If the GPU engine fails at inference time due to missing OpenCL (e.g. Samsung
     * restricts libOpenCL.so for third-party apps), the engine is torn down and
     * reinitialised with CPU, then the same prompt is retried transparently.
     * The OpenCL error fires before any tokens are emitted, so no partial output
     * is lost.
     */
    override fun process(prompt: String): Flow<LlmResult> =
        processInternal(prompt, allowOpenClRetry = true)

    private fun processInternal(prompt: String, allowOpenClRetry: Boolean): Flow<LlmResult> = flow {
        val eng = engine ?: throw IllegalStateException(
            "Model not loaded. ${initFailureReason ?: "Call initialize() first."}"
        )
        eng.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { message ->
                val text = message.toString()
                if (text.isNotEmpty()) emit(LlmResult.Token(text))
            }
            emit(LlmResult.Complete)
        }
    }.catch { e ->
        if (allowOpenClRetry && !openClFailed &&
            e.message?.contains("OpenCL", ignoreCase = true) == true) {
            Log.w(TAG, "GPU inference failed — OpenCL unavailable on this device, switching to CPU")
            openClFailed = true
            withContext(dispatcher) { switchToCpu() }
            emitAll(processInternal(prompt, allowOpenClRetry = false))
        } else {
            emit(LlmResult.Error(e))
        }
    }.flowOn(dispatcher)

    /**
     * Tears down the current engine (releasing native GPU resources if possible)
     * and reinitialises with CPU only. Called when OpenCL inference fails at runtime.
     */
    private fun switchToCpu() {
        synchronized(initLock) {
            (engine as? AutoCloseable)?.runCatching { close() }
            engine = null
            initAttempted = false
            initFailureReason = null
            _modelLoadState.value = ModelLoadState.IDLE
        }
        initialize() // openClFailed = true, so backends = [CPU]
    }

    private fun isQualcommDevice(): Boolean {
        val hardware = Build.HARDWARE.lowercase(java.util.Locale.ROOT)
        val board = Build.BOARD.lowercase(java.util.Locale.ROOT)
        return hardware == "qcom" || board.contains("sm8") || board.contains("sdm") ||
                board == "sun" || board == "kailua" || board == "pineapple" || board == "kalama"
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"

        // Single model file: gemma3-1b-it-int4.litertlm from litert-community/Gemma3-1B-IT.
        // Supports both CPU and GPU (OpenCL JIT) via LiteRT-LM runtime.
        // Must be hosted at masked-kunsiquat/gemma-3-1b-it-litert on HuggingFace (public).
        // DO NOT use the _sm8750/_sm8650/_sm8550 litert-community files — those are
        // NPU/QNN-compiled and will fail on both Backend.GPU and Backend.CPU.
        private const val HF_BASE_URL =
            "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main"

        const val MODEL_FILE = "gemma3-1b-it-int4.litertlm" // 584 MB — all devices
    }
}
