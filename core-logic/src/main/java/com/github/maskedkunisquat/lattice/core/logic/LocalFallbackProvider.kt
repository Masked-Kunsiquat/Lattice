package com.github.maskedkunisquat.lattice.core.logic

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM provider backed by the locally-bundled Llama-3.2-3B-Instruct ONNX model (Q4).
 *
 * This is the primary fallback when Gemini Nano (AICore) is unavailable. The model
 * runs entirely on-device via ONNX Runtime — no data leaves the device.
 *
 * ## Asset setup
 * Place the following files in app/src/main/assets/:
 *   model_q4.onnx          — model graph
 *   model_q4.onnx_data     — external weight shard 0
 *   model_q4.onnx_data_1   — external weight shard 1
 *
 * ## External data handling
 * ONNX Runtime requires all weight shards to reside beside the .onnx file on the
 * real filesystem (not in the APK asset stream). On first [initialize], all three
 * files are copied from assets to [Context.filesDir]. Subsequent launches skip the
 * copy if the files are already present.
 *
 * ## Hardware acceleration
 * NNAPI is requested first, routing eligible ops to the Snapdragon 8 Elite's NPU/GPU.
 * If NNAPI initialisation fails (unsupported device or driver issue) the session falls
 * back to the CPU execution provider transparently.
 *
 * TODO (Task 5.1 — Unified Reframing Loop):
 *   1. Bundle tokenizer.json (Llama-3 BPE) and implement LlamaTokenizer
 *   2. Format prompt with the Llama-3 chat template (<|begin_of_text|> etc.)
 *   3. Run autoregressive decode loop: forward pass → sample next token → repeat
 *   4. Emit each LlmResult.Token; terminate on EOS (<|eot_id|>) or max_new_tokens
 */
class LocalFallbackProvider(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmProvider {

    override val id = "llama3_onnx_local"

    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    @Volatile private var initAttempted = false

    /**
     * Copies the model shards from assets to internal storage (if needed) then
     * opens an [OrtSession] with NNAPI acceleration. Silent on failure —
     * [isAvailable] returns false and the orchestrator handles the fallback.
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    fun initialize() {
        if (initAttempted) return
        initAttempted = true
        try {
            copyAssetsToFilesDir()
            val modelPath = File(context.filesDir, MODEL_ASSET).absolutePath
            session = createSession(modelPath)
        } catch (e: Exception) {
            Log.w(TAG, "LocalFallbackProvider init failed — provider unavailable", e)
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (!initAttempted) withContext(dispatcher) { initialize() }
        return session != null
    }

    override fun process(prompt: String): Flow<LlmResult> = flow {
        if (session == null) {
            emit(
                LlmResult.Error(
                    IllegalStateException(
                        "Llama-3.2-3B model not loaded. Ensure $MODEL_ASSET and its " +
                        "data shards are in app/src/main/assets/ and call " +
                        "LocalFallbackProvider.initialize() at app startup."
                    )
                )
            )
            return@flow
        }

        // TODO: Replace stub with real autoregressive inference (Task 5.1).
        emit(LlmResult.Error(UnsupportedOperationException("Llama-3.2-3B inference not yet implemented.")))
    }.flowOn(dispatcher)

    // ── Internals ────────────────────────────────────────────────────────────

    /**
     * Copies each asset shard to [Context.filesDir] only if not already present.
     * All three files must be co-located so ORT can resolve the external-data path.
     */
    private fun copyAssetsToFilesDir() {
        for (asset in ASSET_FILES) {
            val dest = File(context.filesDir, asset)
            if (!dest.exists()) {
                context.assets.open(asset).use { src ->
                    dest.outputStream().use { dst -> src.copyTo(dst) }
                }
            }
        }
    }

    /**
     * Opens an [OrtSession] from [modelPath], preferring NNAPI (NPU/GPU) and
     * falling back to CPU if NNAPI is unavailable on this device.
     */
    private fun createSession(modelPath: String): OrtSession {
        return try {
            val opts = OrtSession.SessionOptions().apply { addNnapi() }
            env.createSession(modelPath, opts)
        } catch (_: Exception) {
            // NNAPI unavailable or unsupported ops — retry with CPU-only session.
            Log.i(TAG, "NNAPI unavailable, falling back to CPU execution provider.")
            env.createSession(modelPath, OrtSession.SessionOptions())
        }
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"
        private const val MODEL_ASSET = "model_q4.onnx"
        private val ASSET_FILES = listOf(
            "model_q4.onnx",
            "model_q4.onnx_data",
            "model_q4.onnx_data_1",
        )
    }
}
