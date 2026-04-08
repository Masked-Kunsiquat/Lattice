package com.github.maskedkunisquat.lattice.core.logic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
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
    private var tokenizer: WordPieceTokenizer? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * Loads the ONNX model and WordPiece vocabulary from assets.
     * Safe to call on any thread; failures are logged and the provider falls back to
     * zero-vectors so the app remains functional if assets are missing.
     */
    fun initialize(context: Context) {
        try {
            val vocabLines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            tokenizer = WordPieceTokenizer(vocabLines)
            val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
            ortSession = env.createSession(modelBytes, OrtSession.SessionOptions())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize embedding model: ${e.message}")
            // Model or vocab not yet available — generateEmbedding will return zero-vectors.
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
        val tok = tokenizer ?: return@withContext FloatArray(EMBEDDING_DIM)
        runInference(session, tok, text)
    }

    /**
     * Tokenizes [text] with [tokenizer] and runs ONNX inference.
     *
     * Input tensors: input_ids [1 × seq_len], attention_mask [1 × seq_len]
     * Output tensor: [1 × seq_len × 384] — mean-pooled to [384].
     */
    private fun runInference(session: OrtSession, tokenizer: WordPieceTokenizer, text: String): FloatArray {
        val (inputIdsArr, attentionMaskArr) = tokenizer.encode(text)
        val seqLen = inputIdsArr.size.toLong()
        val shape = longArrayOf(1L, seqLen)

        return OnnxTensor.createTensor(env, LongBuffer.wrap(inputIdsArr), shape).use { inputIdsTensor ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMaskArr), shape).use { attentionMaskTensor ->
                val inputs = mapOf("input_ids" to inputIdsTensor, "attention_mask" to attentionMaskTensor)
                session.run(inputs).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = (result[0].value as Array<Array<FloatArray>>)[0]
                    meanPool(raw)
                }
            }
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
        private const val VOCAB_ASSET = "vocab.txt"
        private const val TAG = "EmbeddingProvider"
    }
}
