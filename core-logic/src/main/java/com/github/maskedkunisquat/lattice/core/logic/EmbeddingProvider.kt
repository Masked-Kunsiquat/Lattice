package com.github.maskedkunisquat.lattice.core.logic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.LongBuffer

/**
 * Generates 384-dimensional sentence embeddings using the Snowflake Arctic Embed XS ONNX model.
 *
 * Call [initialize] once (e.g., at app startup) before invoking [generateEmbedding].
 * If the model asset is absent the provider falls back to zero-vectors, so the app
 * remains functional during development before the real model is bundled.
 *
 * @param dispatcher Dispatcher for inference work — defaults to [Dispatchers.Default] so
 *                   inference never blocks the main thread. Inject a test dispatcher in tests.
 */
open class EmbeddingProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var ortSession: OrtSession? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Loads the ONNX model from assets. Safe to call on any thread; failures are swallowed
     * so the app still launches when the model placeholder is not yet a real file.
     */
    fun initialize(context: Context) {
        try {
            val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
            ortSession = env.createSession(modelBytes, OrtSession.SessionOptions())
        } catch (_: Exception) {
            // Model not yet available — generateEmbedding will return zero-vectors.
        }
    }

    /**
     * Embeds [text] into a 384-dimensional float vector.
     *
     * IMPORTANT: [text] must already be PII-masked before calling this function.
     * Raw user text must never enter the embedding pipeline.
     *
     * Suspends and resumes on [dispatcher] (default: [Dispatchers.Default]).
     */
    open suspend fun generateEmbedding(text: String): FloatArray = withContext(dispatcher) {
        val session = ortSession ?: return@withContext FloatArray(EMBEDDING_DIM)
        runInference(session, text)
    }

    /**
     * Runs tokenization and ONNX inference.
     *
     * TODO: Replace the stub tokenizer with a real WordPiece / BPE implementation
     *       (e.g., bundle the vocab.txt from HuggingFace and implement tokenization here,
     *       or use a Kotlin tokenizer library). The model expects:
     *         - input_ids       : LongBuffer [1 × seq_len]
     *         - attention_mask  : LongBuffer [1 × seq_len]
     *       and produces a float tensor [1 × seq_len × 384] that must be mean-pooled.
     */
    private fun runInference(session: OrtSession, text: String): FloatArray {
        // Stub: deterministic zero-vector until tokenizer is wired up.
        // Replace this body once vocab.txt is bundled alongside the ONNX model.
        val seqLen = 1L
        val inputIds = LongBuffer.wrap(LongArray(seqLen.toInt()) { 0L })
        val attentionMask = LongBuffer.wrap(LongArray(seqLen.toInt()) { 1L })

        val shape = longArrayOf(1L, seqLen)
        val inputIdsTensor = OnnxTensor.createTensor(env, inputIds, shape)
        val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask, shape)

        val inputs = mapOf("input_ids" to inputIdsTensor, "attention_mask" to attentionMaskTensor)
        session.run(inputs).use { result ->
            // Mean-pool over sequence dimension → [1 × EMBEDDING_DIM]
            @Suppress("UNCHECKED_CAST")
            val raw = (result[0].value as Array<Array<FloatArray>>)[0]
            return meanPool(raw)
        }
    }

    private fun meanPool(tokenVectors: Array<FloatArray>): FloatArray {
        val pooled = FloatArray(EMBEDDING_DIM)
        for (token in tokenVectors) {
            for (i in token.indices) pooled[i] += token[i]
        }
        val n = tokenVectors.size.toFloat()
        for (i in pooled.indices) pooled[i] /= n
        return pooled
    }

    companion object {
        const val EMBEDDING_DIM = 384
        private const val MODEL_ASSET = "snowflake-arctic-embed-xs.onnx"
    }
}
