package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generates 384-dimensional sentence embeddings using the Snowflake Arctic Embed XS TFLite model.
 *
 * Call [initialize] once (e.g., at app startup) before invoking [generateEmbedding].
 * If the model asset is absent the provider falls back to zero-vectors, so the app
 * remains functional during development before the real model is bundled.
 *
 * Model: fixed seq_len=128 (baked in at export). Inputs are INT64. Output is
 * `last_hidden_state` [1 × 128 × 384] — masked mean-pooled to [384] using attention_mask.
 *
 * @param dispatcher Dispatcher for inference work — defaults to [Dispatchers.Default] so
 *                   inference never blocks the main thread. Inject a test dispatcher in tests.
 */
open class EmbeddingProvider(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var interpreter: Interpreter? = null
    private var tokenizer: WordPieceTokenizer? = null
    private val fallbackLoggedInterpreter = AtomicBoolean(false)
    private val fallbackLoggedTokenizer = AtomicBoolean(false)

    /**
     * Loads the TFLite model and WordPiece vocabulary from assets.
     * Safe to call on any thread; failures are logged and the provider falls back to
     * zero-vectors so the app remains functional if assets are missing.
     */
    fun initialize(context: Context) {
        try {
            val vocabLines = context.assets.open(VOCAB_ASSET).bufferedReader().readLines()
            tokenizer = WordPieceTokenizer(vocabLines)

            val modelBytes = context.assets.open(MODEL_ASSET).readBytes()
            val buf = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
                .apply { put(modelBytes); rewind() }

            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(buf, options)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize embedding model: ${e.message}")
            // Model or vocab not yet available — generateEmbedding will return zero-vectors.
        }
    }

    /** True only after [initialize] has loaded both the TFLite model and the vocabulary. */
    val isInitialized: Boolean get() = interpreter != null && tokenizer != null

    /**
     * Embeds [text] into a 384-dimensional float vector.
     *
     * IMPORTANT: [text] must already be PII-masked before calling this function.
     * Raw user text must never enter the embedding pipeline.
     *
     * Suspends and resumes on [dispatcher] (default: [Dispatchers.Default]).
     */
    open suspend fun generateEmbedding(text: String): FloatArray = withContext(dispatcher) {
        val interp = interpreter ?: run {
            if (fallbackLoggedInterpreter.compareAndSet(false, true)) {
                Log.w(TAG, "generateEmbedding returning zero-vector: model not initialized. " +
                    "Call initialize(context) at app startup. isInitialized=$isInitialized")
            }
            return@withContext FloatArray(EMBEDDING_DIM)
        }
        val tok = tokenizer ?: run {
            if (fallbackLoggedTokenizer.compareAndSet(false, true)) {
                Log.w(TAG, "generateEmbedding returning zero-vector: tokenizer not initialized. " +
                    "Call initialize(context) at app startup. isInitialized=$isInitialized")
            }
            return@withContext FloatArray(EMBEDDING_DIM)
        }
        runInference(interp, tok, text)
    }

    /**
     * Tokenizes [text], pads/truncates to [MODEL_SEQ_LEN], and runs TFLite inference.
     *
     * Input tensors (both INT64, shape [1 × MODEL_SEQ_LEN]):
     *   - input_ids
     *   - attention_mask
     *
     * Output tensor: last_hidden_state [1 × MODEL_SEQ_LEN × 384]
     * Pooling: masked mean — average only over positions where attention_mask == 1.
     */
    private fun runInference(
        interp: Interpreter,
        tokenizer: WordPieceTokenizer,
        text: String
    ): FloatArray {
        val (rawIds, rawMask) = tokenizer.encode(text)

        // Pad or truncate to fixed model seq_len.
        val inputIds  = LongArray(MODEL_SEQ_LEN)
        val attnMask  = LongArray(MODEL_SEQ_LEN)
        val copyLen   = minOf(rawIds.size, MODEL_SEQ_LEN)
        for (i in 0 until copyLen) {
            inputIds[i] = rawIds[i]
            attnMask[i] = rawMask[i]
        }

        // token_type_ids: all-zeros for single-sequence inference (standard BERT convention).
        val tokenTypeIds = LongArray(MODEL_SEQ_LEN)

        // Wrap into [1 × MODEL_SEQ_LEN] 2-D arrays for the Interpreter.
        val inputIdsBatch    = Array(1) { inputIds }
        val attnMaskBatch    = Array(1) { attnMask }
        val tokenTypeIdsBatch = Array(1) { tokenTypeIds }

        // Output buffer: [1 × MODEL_SEQ_LEN × 384] float32.
        val output = Array(1) { Array(MODEL_SEQ_LEN) { FloatArray(EMBEDDING_DIM) } }

        val inputs = mapOf(
            "input_ids"       to inputIdsBatch,
            "attention_mask"  to attnMaskBatch,
            "token_type_ids"  to tokenTypeIdsBatch,
        )
        val outputs = mapOf("last_hidden_state" to output)
        interp.runSignature(inputs, outputs)

        return maskedMeanPool(output[0], attnMask)
    }

    /**
     * Mean-pools [tokenVectors] weighted by [mask] (1 = real token, 0 = padding).
     * Falls back to a plain mean if the mask sums to zero (empty input guard).
     */
    private fun maskedMeanPool(tokenVectors: Array<FloatArray>, mask: LongArray): FloatArray {
        val pooled = FloatArray(EMBEDDING_DIM)
        var count = 0
        for (i in tokenVectors.indices) {
            if (mask[i] == 1L) {
                for (j in 0 until EMBEDDING_DIM) pooled[j] += tokenVectors[i][j]
                count++
            }
        }
        val n = if (count > 0) count.toFloat() else tokenVectors.size.toFloat()
        for (i in pooled.indices) pooled[i] /= n
        return pooled
    }

    companion object {
        const val EMBEDDING_DIM = 384
        const val MODEL_SEQ_LEN = 128
        private const val MODEL_ASSET = "snowflake-arctic-embed-xs_float32.tflite"
        private const val VOCAB_ASSET = "vocab.txt"
        private const val TAG = "EmbeddingProvider"
    }
}
