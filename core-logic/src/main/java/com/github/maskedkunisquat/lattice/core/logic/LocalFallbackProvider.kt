package com.github.maskedkunisquat.lattice.core.logic

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * LLM provider backed by a locally-bundled Qwen-1.5B ONNX model.
 *
 * This is the primary fallback when Gemini Nano (AICore) is unavailable. The model
 * runs entirely on-device via ONNX Runtime, so no data ever leaves the device.
 *
 * Setup: Place the ONNX export of Qwen-1.5B in:
 *   core-logic/src/main/assets/qwen-1.5b.onnx
 *
 * TODO: Implement autoregressive decoding once the model asset is bundled:
 *   1. Tokenize prompt with Qwen BPE vocabulary (bundle vocab.json alongside model)
 *   2. Run forward pass → logits
 *   3. Sample next token (greedy or top-p)
 *   4. Repeat until EOS token or max_new_tokens reached
 *   5. Emit each decoded token as LlmResult.Token
 */
class LocalFallbackProvider(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : LlmProvider {

    override val id = "qwen_onnx_local"

    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()

    /**
     * Loads the Qwen ONNX model from assets. Silent on failure — [isAvailable]
     * will return false and the orchestrator will handle the fallback.
     */
    fun initialize() {
        try {
            val bytes = context.assets.open(MODEL_ASSET).readBytes()
            session = env.createSession(bytes, OrtSession.SessionOptions())
        } catch (_: Exception) {
            // Model not yet bundled — isAvailable() returns false.
        }
    }

    override suspend fun isAvailable(): Boolean = withContext(dispatcher) {
        session != null
    }

    override fun process(prompt: String): Flow<LlmResult> = flow {
        if (session == null) {
            emit(
                LlmResult.Error(
                    IllegalStateException(
                        "Qwen-1.5B model not loaded. Place $MODEL_ASSET in core-logic/src/main/assets/ " +
                        "and call LocalFallbackProvider.initialize() at app startup."
                    )
                )
            )
            return@flow
        }

        // TODO: Replace stub with real autoregressive inference once model is bundled.
        emit(LlmResult.Error(UnsupportedOperationException("Qwen inference not yet implemented.")))
    }.flowOn(dispatcher)

    companion object {
        private const val MODEL_ASSET = "qwen-1.5b.onnx"
    }
}
