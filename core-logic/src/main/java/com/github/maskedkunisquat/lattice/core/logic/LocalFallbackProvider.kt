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
 * ## Hardware tiers
 * Three variants are hosted at `masked-kunsiquat/gemma-3-1b-it-litert` on HuggingFace
 * (sourced from [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT)).
 * The model file is selected at [initialize] time based on [Build.BOARD]:
 *
 * | Tier | File | Target SoC | Backend |
 * |---|---|---|---|
 * | Elite | `gemma3-1b-it-elite.litertlm` | SM8750 (S25 Ultra) | Adreno 830 AOT → GPU |
 * | Ultra | `gemma3-1b-it-ultra.litertlm` | SM8650 (S24 Ultra) | Adreno 750 AOT → GPU |
 * | Universal | `gemma3-1b-it-universal.litertlm` | Any ARM64 | CPU |
 *
 * Run `./gradlew downloadModels` to fetch the correct variant for the connected device.
 *
 * ## Model lifecycle
 * The [Engine] is a heavy singleton created in [initialize] and kept alive for the
 * lifetime of the provider. Each [process] call creates a fresh [Conversation] (no
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
     * Creates the [Engine] for the selected hardware tier and marks the provider ready.
     * Safe to call multiple times — subsequent calls are no-ops once the engine is loaded.
     * Retry is automatic after a failure (e.g. after a fresh download replaces a corrupt file).
     *
     * GPU is attempted first on Snapdragon devices. If the model file does not contain
     * AOT-compiled Adreno kernels (i.e. it is a CPU-quantised community model), the runtime
     * throws [LiteRtLmJniException] with "Input tensor not found". In that case we
     * transparently retry with [Backend.CPU] so inference still works.
     */
    fun initialize() {
        if (engine != null) return
        synchronized(initLock) {
            if (engine != null) return
            initAttempted = true

            val modelAsset = resolveModelName()
            Log.i(TAG, "Selected model tier: $modelAsset (board=${Build.BOARD}, hardware=${Build.HARDWARE})")

            val modelFile = File(context.filesDir, modelAsset)
            if (!modelFile.exists()) {
                _modelLoadState.value = ModelLoadState.ERROR
                initFailureReason = "Model not found: $modelAsset. Download it from Settings."
                return
            }

            _modelLoadState.value = ModelLoadState.LOADING_SESSION

            // Prefer GPU for Snapdragon tiers; fall back to CPU when the model file
            // lacks AOT-compiled Adreno kernels (community quantised models).
            val preferredBackend = resolveBackend()
            val backendsToTry: List<Backend> =
                if (preferredBackend is Backend.GPU) listOf(preferredBackend, Backend.CPU())
                else listOf(Backend.CPU())

            var lastException: Exception? = null
            for (backend in backendsToTry) {
                try {
                    val engineConfig = EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = context.cacheDir.path,
                    )
                    val eng = Engine(engineConfig)
                    eng.initialize()
                    engine = eng
                    _modelLoadState.value = ModelLoadState.READY
                    Log.i(TAG, "LiteRT-LM engine ready — model=$modelAsset backend=${backend::class.simpleName}")
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
            // Delete the model file on any engine-creation failure so the next
            // "Download" tap fetches a fresh copy. Covers format/signature mismatches
            // (LiteRtLmJniException), truncated downloads, and version skew.
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
     * Streams inference results for [prompt] via a fresh LiteRT-LM [Conversation].
     *
     * A new [Conversation] is created per call — [ReframingLoop] manages all multi-turn
     * context externally and passes a self-contained prompt each time.
     *
     * Emits [LlmResult.Token] for each streamed chunk, then [LlmResult.Complete] on
     * finish, or [LlmResult.Error] on failure.
     */
    override fun process(prompt: String): Flow<LlmResult> = flow {
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
        emit(LlmResult.Error(e))
    }.flowOn(dispatcher)

    // ── Tier selection ────────────────────────────────────────────────────────

    /**
     * Returns the model asset filename for the current device based on [Build.BOARD]
     * and [Build.HARDWARE].
     *
     * Board → SoC mapping:
     * - "sun" / "kailua"   → SM8750 Snapdragon 8 Elite  (S25 series, Pixel 9 series)
     * - "pineapple"        → SM8650 Snapdragon 8 Gen 3  (S24 series)
     * - "kalama"           → SM8550 Snapdragon 8 Gen 2  (S23 series)
     * - anything else      → universal CPU model
     *
     * Note: "kalama" is SM8550, NOT SM8650 — an earlier mapping was incorrect.
     */
    private fun resolveModelName(): String {
        val board = Build.BOARD.lowercase(java.util.Locale.ROOT)
        val hardware = Build.HARDWARE.lowercase(java.util.Locale.ROOT)

        return when {
            board == "sun" || board == "kailua" || (hardware == "qcom" && board.contains("8750")) -> MODEL_SM8750
            board == "pineapple" || board.contains("8650") -> MODEL_SM8650
            board == "kalama" || board.contains("8550") -> MODEL_SM8550
            else -> MODEL_UNIVERSAL
        }
    }

    /**
     * Returns [Backend.GPU] for Snapdragon tiers with AOT-compiled Adreno kernels,
     * [Backend.CPU] for the universal model. [initialize] will fall back to CPU
     * automatically if GPU init fails.
     */
    private fun resolveBackend(): Backend {
        val board = Build.BOARD.lowercase(java.util.Locale.ROOT)
        val hardware = Build.HARDWARE.lowercase(java.util.Locale.ROOT)

        return when {
            board == "sun" || board == "kailua" || (hardware == "qcom" && board.contains("8750")) -> Backend.GPU()
            board == "pineapple" || board.contains("8650") -> Backend.GPU()
            board == "kalama" || board.contains("8550") -> Backend.GPU()
            else -> Backend.CPU()
        }
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"

        // Files hosted at masked-kunsiquat/gemma-3-1b-it-litert on HuggingFace (public, no auth).
        // Source: litert-community/Gemma3-1B-IT — AOT-compiled with Adreno GPU kernels per SoC.
        // Universal is CPU-only (q4 quantised, any ARM64).
        //
        // To add SM8550 support: upload Gemma3-1B-IT_q4_ekv1280_sm8550.litertlm from
        // litert-community renamed to gemma3-1b-it-prime.litertlm, then promote MODEL_SM8550
        // from MODEL_UNIVERSAL to its own constant.
        private const val HF_BASE_URL =
            "https://huggingface.co/masked-kunsiquat/gemma-3-1b-it-litert/resolve/main"

        private const val MODEL_SM8750   = "gemma3-1b-it-elite.litertlm"     // 689 MB — SM8750 Snapdragon 8 Elite
        private const val MODEL_SM8650   = "gemma3-1b-it-ultra.litertlm"     // 690 MB — SM8650 Snapdragon 8 Gen 3
        private const val MODEL_SM8550   = "gemma3-1b-it-universal.litertlm" // SM8550 not uploaded yet → CPU fallback
        private const val MODEL_UNIVERSAL = "gemma3-1b-it-universal.litertlm" // 584 MB — any ARM64
    }
}
