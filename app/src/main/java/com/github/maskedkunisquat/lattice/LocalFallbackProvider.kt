package com.github.maskedkunisquat.lattice

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.github.maskedkunisquat.lattice.core.logic.LocalModelProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ModelDownloader
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import com.github.maskedkunisquat.lattice.core.logic.SettingsRepository

/**
 * LLM provider backed by a locally-stored Gemma 3 1B Instruct LiteRT-LM model.
 *
 * This is the primary fallback when Gemini Nano (AICore) is unavailable. Inference
 * runs entirely on-device via the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
 * runtime — no data leaves the device.
 *
 * ## Model tiers
 * Three model files cover different SoC tiers:
 *
 * | File | Target | Backends |
 * |---|---|---|
 * | [MODEL_FILE_ELITE] | SM8750 (S25 Ultra) — NPU/QNN compiled | NPU only |
 * | [MODEL_FILE_ULTRA] | SM8650 (S24 Ultra) — NPU/QNN compiled | NPU only |
 * | [MODEL_FILE_INT4]  | All devices — standard int4 quantised | GPU (OpenCL JIT) + CPU |
 *
 * The NPU files contain Qualcomm Hexagon DSP bytecode compiled for their specific SoC.
 * They have **no CPU or GPU tensors** — they cannot be loaded with [Backend.GPU] or
 * [Backend.CPU]. NPU access is provided by `libcdsprpc.so` (Qualcomm CDK DSP RPC bridge)
 * declared in the manifest via `<uses-native-library android:required="false">`.
 *
 * ## Backend selection
 * [selectModelAndBackends] picks the best file + backend chain for the running device:
 * - SM8750 → elite model, backends: [NPU] (NPU-only compiled)
 * - SM8650 → ultra model, backends: [NPU] (NPU-only compiled)
 * - Other Qualcomm → int4 model, backends: [GPU, CPU] (OpenCL JIT, CPU fallback)
 * - Everything else → int4 model, backends: [CPU]
 *
 * ## OpenCL fallback
 * Samsung restricts `libOpenCL.so` for third-party apps. If GPU inference fails at
 * decode time with an OpenCL error, [openClFailed] is set and the engine is transparently
 * rebuilt with CPU-only via [switchToCpu]. The manifest's `<uses-native-library>` entry
 * for `libOpenCL.so` grants access on compliant OEM builds.
 *
 * ## Model lifecycle
 * The [Engine] is a heavy singleton created in [initialize] and kept alive for the
 * lifetime of the provider. Each [process] call creates a fresh Conversation (no
 * accumulated turn history — [ReframingLoop] manages all context externally).
 */
class LocalFallbackProvider(
    private val context: Context,
    private val modelDownloader: ModelDownloader,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalModelProvider {

    override val id = "gemma3_1b_litertlm"

    @Volatile private var engine: Engine? = null

    @Volatile private var initAttempted = false
    @Volatile private var initFailureReason: String? = null
    // Set to true the first time a GPU inference call fails due to missing OpenCL.
    // Subsequent initialize() calls will skip GPU entirely.
    @Volatile private var openClFailed = false
    private val initLock = Any()
    private val engineLock = ReentrantReadWriteLock()

    private val _modelLoadState = MutableStateFlow(ModelLoadState.IDLE)
    override val modelLoadState: StateFlow<ModelLoadState> = _modelLoadState.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    override val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()

    /**
     * Whether the provider should prefer the CBT fine-tuned model.
     * If true, [initialize] picks [MODEL_FILE_CBT] if it exists.
     * If false, it always falls back to the appropriate base tier (elite/ultra/int4).
     */
    @Volatile
    var useCbtModel: Boolean = true

    init {
        settingsRepository.settings
            .map { it.useCbtModel }
            .distinctUntilChanged()
            .onEach { enabled ->
                val old = useCbtModel
                useCbtModel = enabled
                // If we've already initialized, we need to re-initialize to switch models.
                if (old != enabled && initAttempted) {
                    withContext(dispatcher) { initialize() }
                }
            }
            .launchIn(scope)
    }

    /**
     * Triggers the [ModelDownloadWorker] to fetch the appropriate model file for this device.
     * UI should observe [getDownloadWorkInfo] to track progress.
     */
    override fun downloadModel() {
        val (modelFile, _) = selectModelAndBackends()
        val sha256 = MODEL_SHA256[modelFile]
        check(sha256 != null || BuildConfig.DEBUG) {
            "SHA-256 digest for $modelFile is not set. Populate MODEL_SHA256 before shipping a release build."
        }
        modelDownloader.enqueue(modelFile, "$HF_BASE_URL/$modelFile", sha256)
    }

    /**
     * Triggers the [ModelDownloadWorker] to fetch the CBT fine-tuned model file.
     * Once downloaded, [initialize] will automatically prefer it over the base tier file.
     * Routes through [ModelDownloadWorker.UNIQUE_WORK_NAME_CBT] so it runs independently
     * of any in-progress base model download.
     */
    fun downloadCbtModel() {
        val sha256 = MODEL_SHA256[MODEL_FILE_CBT]
        check(sha256 != null || BuildConfig.DEBUG) {
            "SHA-256 digest for $MODEL_FILE_CBT is not set. Populate MODEL_SHA256 before shipping a release build."
        }
        modelDownloader.enqueue(MODEL_FILE_CBT, "$HF_BASE_URL/$MODEL_FILE_CBT", sha256)
    }

    /**
     * Creates the [Engine] and marks the provider ready.
     * Safe to call multiple times — no-op once engine is loaded.
     * Automatically resets after a failure so callers can retry (e.g. after
     * [ModelDownloadWorker] replaces a corrupt or incompatible file).
     *
     * Backend priority is determined by [selectModelAndBackends]:
     * - NPU (Hexagon DSP) for Snapdragon 8 Gen 3 / 8 Gen 2 with their NPU-compiled models
     * - GPU (OpenCL JIT) then CPU for all-device int4 model
     */
    override fun initialize() {
        // Force a re-init if the engine is already loaded but with the wrong model file.
        val (targetFile, _) = selectModelAndBackends()
        if (engine != null && _loadedModelName.value == targetFile) return

        synchronized(initLock) {
            if (engine != null && _loadedModelName.value == targetFile) return
            
            // Close existing engine if switching models
            if (engine != null) {
                Log.i(TAG, "Closing existing engine to switch model: ${_loadedModelName.value} -> $targetFile")
                engineLock.writeLock().withLock {
                    try {
                        (engine as? AutoCloseable)?.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error closing engine", e)
                    }
                    engine = null
                    _loadedModelName.value = null
                }
            }

            initAttempted = true
            val (modelFileName, backendsToTry) = selectModelAndBackends()
            val modelFile = File(context.filesDir, modelFileName)
            Log.i(TAG, "Initialising engine — file=$modelFileName board=${Build.BOARD} hardware=${Build.HARDWARE}")

            if (!modelFile.exists()) {
                _modelLoadState.value = ModelLoadState.ERROR
                initFailureReason = "Model not found: $modelFileName. Download it from Settings."
                return
            }

            _modelLoadState.value = ModelLoadState.LOADING_SESSION

            var lastException: Exception? = null
            for (backend in backendsToTry) {
                var eng: Engine? = null
                try {
                    eng = Engine(EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = context.cacheDir.path,
                    ))
                    eng.initialize()
                    engine = eng
                    _loadedModelName.value = modelFileName
                    _modelLoadState.value = ModelLoadState.READY
                    Log.i(TAG, "LiteRT-LM engine ready — backend=${backend::class.simpleName} file=$modelFileName")
                    return
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Engine init failed with backend ${backend::class.simpleName} — ${e.message}")
                    try {
                        (eng as? AutoCloseable)?.close()
                    } catch (_: Exception) { }
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
    override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> =
        processInternal(prompt, systemInstruction, allowOpenClRetry = true)

    private fun processInternal(
        prompt: String,
        systemInstruction: String?,
        allowOpenClRetry: Boolean,
    ): Flow<LlmResult> = flow {
        val eng = engineLock.readLock().withLock {
            engine ?: throw IllegalStateException(
                "Model not loaded. ${initFailureReason ?: "Call initialize() first."}"
            )
        }

        // We MUST hold the read lock while the engine is in use (during inference)
        // to prevent initialize() or switchToCpu() from closing it underneath us.
        engineLock.readLock().lock()
        try {
            val config = if (systemInstruction != null) {
                ConversationConfig(systemInstruction = Contents.of(systemInstruction))
            } else {
                ConversationConfig()
            }
            eng.createConversation(config).use { conversation ->
                conversation.sendMessageAsync(prompt).collect { message ->
                    val text = message.toString()
                    if (text.isNotEmpty()) emit(LlmResult.Token(text))
                }
                emit(LlmResult.Complete)
            }
        } finally {
            engineLock.readLock().unlock()
        }
    }.catch { e ->
        if (allowOpenClRetry && !openClFailed &&
            e.message?.contains("OpenCL", ignoreCase = true) == true) {
            Log.w(TAG, "GPU inference failed — OpenCL unavailable on this device, switching to CPU")
            openClFailed = true
            withContext(dispatcher) { switchToCpu() }
            emitAll(processInternal(prompt, systemInstruction, allowOpenClRetry = false))
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
            engineLock.writeLock().withLock {
                (engine as? AutoCloseable)?.runCatching { close() }
                engine = null
                _loadedModelName.value = null
                initAttempted = false
                initFailureReason = null
                _modelLoadState.value = ModelLoadState.IDLE
            }
        }
        initialize() // openClFailed = true, so backends = [CPU]
    }

    /**
     * Returns (modelFileName, backendsToTry) for the running device.
     *
     * NPU-compiled models (elite/ultra) are Hexagon DSP bytecode — they only work
     * with [Backend.NPU] and have no CPU/GPU tensors. The int4 model supports both
     * [Backend.GPU] (OpenCL JIT) and [Backend.CPU].
     *
     * [openClFailed] skips GPU on subsequent calls after a runtime OpenCL error.
     *
     * NPU requires the LiteRT dispatch runtime (`libpenguin.so`) to be extracted into
     * the APK's native library directory. If it is absent, LiteRT calls abort() —
     * not a catchable exception — so we must guard the NPU path before attempting init.
     */
    private fun selectModelAndBackends(): Pair<String, List<Backend>> {
        // Prefer the CBT fine-tuned model when it has been downloaded AND enabled in settings.
        val cbtFile = File(context.filesDir, MODEL_FILE_CBT)
        if (useCbtModel && cbtFile.exists() && cbtFile.length() > 100_000_000L) {
            val backends = if (!openClFailed) listOf(Backend.GPU(), Backend.CPU())
                           else listOf(Backend.CPU())
            Log.i(TAG, "CBT model selected — using $MODEL_FILE_CBT")
            return MODEL_FILE_CBT to backends
        }

        val board = Build.BOARD.lowercase(java.util.Locale.ROOT)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val dispatchLibAvailable = java.io.File(nativeLibDir, "libpenguin.so").exists()
        if (!dispatchLibAvailable) {
            Log.w(TAG, "libpenguin.so not found in $nativeLibDir — NPU backend unavailable, using int4 model")
        }
        return when {
            // SM8750 (S25 Ultra / Pixel 9 Pro) — NPU-compiled elite model.
            // "kailua" is the SM8750 board name on Pixel 9 Pro devices.
            // libpenguin.so must be present in nativeLibDir; if absent LiteRT aborts (SIGABRT).
            (board == "sun" || board == "kailua" || board.startsWith("sm8750")) && dispatchLibAvailable ->
                MODEL_FILE_ELITE to listOf(Backend.NPU(nativeLibraryDir = nativeLibDir))
            // SM8650 (S24 Ultra) — NPU-compiled ultra model.
            (board == "kalama" || board.startsWith("sm8650")) && dispatchLibAvailable ->
                MODEL_FILE_ULTRA to listOf(Backend.NPU(nativeLibraryDir = nativeLibDir))
            // Other Qualcomm — int4 model with GPU→CPU
            isQualcommDevice() -> {
                val backends = if (!openClFailed) listOf(Backend.GPU(), Backend.CPU())
                               else listOf(Backend.CPU())
                MODEL_FILE_INT4 to backends
            }
            // Non-Qualcomm — int4 model, CPU only
            else -> MODEL_FILE_INT4 to listOf(Backend.CPU())
        }
    }

    private fun isQualcommDevice(): Boolean {
        val hardware = Build.HARDWARE.lowercase(java.util.Locale.ROOT)
        val board = Build.BOARD.lowercase(java.util.Locale.ROOT)
        return hardware == "qcom" || board.contains("sm8") || board.contains("sdm") ||
                board == "sun" || board == "kailua" || board == "pineapple" || board == "kalama"
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"

        private const val HF_BASE_URL =
            "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main"

        // NPU/QNN-compiled models — Hexagon DSP bytecode, Backend.NPU only.
        // DO NOT load these with Backend.GPU or Backend.CPU — they have no CPU/GPU tensors.
        const val MODEL_FILE_ELITE = "gemma3-1b-it-elite.litertlm"   // SM8750 (S25 Ultra)
        const val MODEL_FILE_ULTRA = "gemma3-1b-it-ultra.litertlm"   // SM8650 (S24 Ultra)

        // Standard int4-quantised model — CPU + GPU (OpenCL JIT) via LiteRT-LM runtime.
        // For all other devices. ~584 MB.
        const val MODEL_FILE_INT4  = "gemma3-1b-it-int4.litertlm"

        // CBT fine-tuned model — merged LoRA weights, INT8 quantisation.
        // Preferred over the base tier files when present in filesDir.
        // Download via downloadCbtModel(); upload to HF repo after offline training completes.
        const val MODEL_FILE_CBT   = "gemma3-1b-it-cbt-int8.litertlm"

        // Convenience alias — the file this device will download/use.
        // (Used by external callers that don't have a Context to call selectModelAndBackends.)
        const val MODEL_FILE = MODEL_FILE_INT4

        /**
         * Expected SHA-256 digests for each model file (hex, lowercase).
         * Null entries mean integrity verification is skipped for that tier.
         * TODO: populate from HuggingFace model card checksums before shipping.
         */
        internal val MODEL_SHA256: Map<String, String?> = mapOf(
            MODEL_FILE_ELITE to "1904ceff9591e7a140df3a672c800e8e7bee8337526484b00f69ccef4fa2d60a",
            MODEL_FILE_ULTRA to "85d2ea5199802f913818d53897b3a304bcf983abb993393e6b1749fbdb005552",
            MODEL_FILE_INT4  to "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
            MODEL_FILE_CBT   to "e8f6c82183c70a4fcdc5a2512c182ff14ac77e7a302f78eab87d40e0189fe758",
        )
    }
}
