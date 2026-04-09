package com.github.maskedkunisquat.lattice.core.logic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * LLM provider backed by the locally-bundled Llama-3.2-3B-Instruct ONNX model (Q4).
 *
 * This is the primary fallback when Gemini Nano (AICore) is unavailable. The model
 * runs entirely on-device via ONNX Runtime — no data leaves the device.
 *
 * ## Asset setup
 * Place the following files in app/src/main/assets/:
 *   model_q4.onnx              — model graph
 *   model_q4.onnx_data         — external weight shard 0
 *   model_q4.onnx_data_1       — external weight shard 1
 *   tokenizer.json             — Llama-3 BPE vocabulary and merge rules
 *   tokenizer_config.json      — tokenizer metadata
 *   generation_config.json     — default generation parameters
 *   config.json                — model architecture (vocab_size, num_key_value_heads, head_dim)
 *
 * ## Inference loop
 * Uses a KV-cached autoregressive decode loop:
 *   1. Tokenise the prompt with [LlamaTokenizer] (BPE, Llama-3 chat template).
 *   2. First forward pass: full prompt, empty KV cache.
 *   3. Greedy-sample the next token from the last position's logits.
 *   4. Emit [LlmResult.Token] (streaming to UI).
 *   5. Subsequent passes: single new token + accumulated KV cache.
 *   6. Terminate on EOS (128001 / 128008 / 128009) or [MAX_NEW_TOKENS].
 *
 * ## Hardware acceleration
 * NNAPI is requested first, routing eligible ops to the Snapdragon 8 Elite's NPU/GPU.
 * If NNAPI initialisation fails the session falls back to CPU transparently.
 */
class LocalFallbackProvider(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmProvider {

    override val id = "llama3_onnx_local"

    @Volatile private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    private val tokenizer = LlamaTokenizer(context)

    // Architecture constants — populated from config.json during initialize(),
    // falling back to compile-time defaults if the asset is missing or malformed.
    private var vocabSize  = VOCAB_SIZE_DEFAULT
    private var numKvHeads = NUM_KV_HEADS_DEFAULT
    private var headDim    = HEAD_DIM_DEFAULT

    @Volatile private var initAttempted = false
    private val initLock = Any()

    /**
     * Copies the model shards from assets to internal storage (if needed) then
     * opens an [OrtSession] with NNAPI acceleration and initialises the tokenizer.
     * Silent on failure — [isAvailable] returns false and the orchestrator handles
     * the fallback. Safe to call multiple times; subsequent calls are no-ops.
     */
    fun initialize() {
        if (initAttempted) return
        synchronized(initLock) {
            if (initAttempted) return
            initAttempted = true
            try {
                loadArchConfig()
                copyAssetsToFilesDir()
                val modelPath = File(context.filesDir, MODEL_ASSET).absolutePath
                val newSession = createSession(modelPath)
                logSessionInfo()
                tokenizer.initialize()
                // Assign only after both session AND tokenizer are ready;
                // if tokenizer.initialize() throws, session stays null and
                // isAvailable() correctly returns false.
                session = newSession
            } catch (e: Exception) {
                Log.w(TAG, "LocalFallbackProvider init failed — provider unavailable", e)
            }
        }
    }

    override suspend fun isAvailable(): Boolean {
        if (!initAttempted) withContext(dispatcher) { initialize() }
        return session != null
    }

    /**
     * Runs the Llama-3.2-3B autoregressive inference loop on [prompt].
     *
     * Emits [LlmResult.Token] for each decoded token (streaming). Terminates with
     * [LlmResult.Complete] on EOS or when [MAX_NEW_TOKENS] is reached, or
     * [LlmResult.Error] on any inference failure.
     */
    override fun process(prompt: String): Flow<LlmResult> = flow {
        val sess = session
        if (sess == null) {
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

        try {
            val promptTokens = tokenizer.encode(prompt, allowSpecialTokens = true)
            if (promptTokens.isEmpty()) {
                emit(LlmResult.Complete)
                return@flow
            }

            // Discover KV cache layer count from the session's declared inputs.
            val numLayers = sess.inputInfo.keys
                .count { it.startsWith("past_key_values.") && it.endsWith(".key") }
            if (numLayers == 0) {
                emit(LlmResult.Error(IllegalStateException(
                    "Could not find past_key_values inputs in model. Inputs: ${sess.inputInfo.keys}"
                )))
                return@flow
            }

            // KV cache: Array<Pair<keyFloats, valueFloats>>, both start empty.
            var kvCache = Array(numLayers) { Pair(FloatArray(0), FloatArray(0)) }
            var pastLen = 0
            val byteBuffer = ByteArrayOutputStream()

            // ── First pass: encode the full prompt ───────────────────────────
            var result = runForwardPass(
                sess        = sess,
                inputIds    = promptTokens,
                pastLen     = 0,
                kvCache     = kvCache,
                numLayers   = numLayers,
            )
            kvCache = result.presentKv
            pastLen = promptTokens.size

            var nextTokenId = greedySample(result.logits, 0)
            result.logits = FloatArray(0) // release memory

            // Streaming byte buffer for UTF-8 reconstruction
            byteBuffer.write(tokenizer.decodeToBytes(nextTokenId))
            val text = flushUtf8(byteBuffer)
            if (text.isNotEmpty()) emit(LlmResult.Token(text))

            // ── Autoregressive loop ──────────────────────────────────────────
            var newTokensGenerated = 1
            while (!tokenizer.isEos(nextTokenId) && newTokensGenerated < MAX_NEW_TOKENS) {
                currentCoroutineContext().ensureActive()

                result = runForwardPass(
                    sess        = sess,
                    inputIds    = longArrayOf(nextTokenId.toLong()),
                    pastLen     = pastLen,
                    kvCache     = kvCache,
                    numLayers   = numLayers,
                )
                kvCache = result.presentKv
                pastLen++

                nextTokenId = greedySample(result.logits, 0)
                result.logits = FloatArray(0)

                if (!tokenizer.isEos(nextTokenId)) {
                    byteBuffer.write(tokenizer.decodeToBytes(nextTokenId))
                    val chunk = flushUtf8(byteBuffer)
                    if (chunk.isNotEmpty()) emit(LlmResult.Token(chunk))
                }
                newTokensGenerated++
            }

            // Flush any remaining buffered bytes
            if (byteBuffer.size() > 0) {
                emit(LlmResult.Token(byteBuffer.toString(Charsets.UTF_8.name())))
            }

            emit(LlmResult.Complete)
        } catch (e: Exception) {
            emit(LlmResult.Error(e))
        }
    }.flowOn(dispatcher)

    // ── Forward pass ─────────────────────────────────────────────────────────

    private data class ForwardResult(
        var logits: FloatArray,
        val presentKv: Array<Pair<FloatArray, FloatArray>>,
        val numLayers: Int,
    )

    /**
     * Runs a single forward pass through [sess].
     *
     * @param inputIds   Token ids for this step (prompt on first pass, single token thereafter).
     * @param pastLen    Number of tokens in the accumulated KV cache.
     * @param kvCache    Current KV cache per layer (empty float arrays on first call).
     * @param numLayers  Number of transformer layers (discovered from session input names).
     */
    private fun runForwardPass(
        sess: OrtSession,
        inputIds: LongArray,
        pastLen: Int,
        kvCache: Array<Pair<FloatArray, FloatArray>>,
        numLayers: Int,
    ): ForwardResult {
        val seqLen = inputIds.size
        val inputs = mutableMapOf<String, OnnxTensor>()

        try {
            // Standard input tensors
            inputs["input_ids"] = OnnxTensor.createTensor(
                env, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen.toLong())
            )
            inputs["attention_mask"] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(pastLen + seqLen) { 1L }),
                longArrayOf(1, (pastLen + seqLen).toLong())
            )
            inputs["position_ids"] = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(LongArray(seqLen) { (pastLen + it).toLong() }),
                longArrayOf(1, seqLen.toLong())
            )

            // Past KV cache tensors (empty on first pass: shape [1, kvHeads, 0, headDim])
            for (i in 0 until numLayers) {
                val (kd, vd) = kvCache[i]
                val kvPastLen = if (kd.isEmpty()) 0L else pastLen.toLong()
                val kvShape = longArrayOf(1, numKvHeads, kvPastLen, headDim)
                inputs["past_key_values.$i.key"] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(kd), kvShape
                )
                inputs["past_key_values.$i.value"] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(vd), kvShape
                )
            }

            val outputs = sess.run(inputs)
            return outputs.use { out ->
                // Extract logits for the last sequence position
                val logitsTensor = out["logits"].get() as OnnxTensor
                val allLogits = FloatArray(logitsTensor.floatBuffer.remaining())
                logitsTensor.floatBuffer.get(allLogits)
                // Only keep the last position's logits ([1, seqLen, vocab] → [vocab])
                val lastPosLogits = allLogits.copyOfRange(
                    (seqLen - 1) * vocabSize,
                    seqLen * vocabSize
                )

                // Extract present KV cache for the next step
                val presentKv = Array(numLayers) { i ->
                    val kTensor = out["present.$i.key"].get() as OnnxTensor
                    val vTensor = out["present.$i.value"].get() as OnnxTensor
                    val kData = FloatArray(kTensor.floatBuffer.remaining()).also { kTensor.floatBuffer.get(it) }
                    val vData = FloatArray(vTensor.floatBuffer.remaining()).also { vTensor.floatBuffer.get(it) }
                    Pair(kData, vData)
                }

                ForwardResult(logits = lastPosLogits, presentKv = presentKv, numLayers = numLayers)
            }
        } finally {
            // Always release input tensors regardless of output collection success
            inputs.values.forEach { it.close() }
        }
    }

    // ── Sampling ──────────────────────────────────────────────────────────────

    /**
     * Greedy sampling: returns the token id with the highest logit at [offset] in [logits].
     *
     * [offset] addresses the start of a single vocab-sized slice within [logits].
     * When called with a single-step forward pass, offset=0 and logits.size==VOCAB_SIZE.
     */
    private fun greedySample(logits: FloatArray, offset: Int): Int {
        var maxVal = Float.NEGATIVE_INFINITY
        var maxIdx = 0
        for (i in 0 until vocabSize) {
            val v = logits[offset + i]
            if (v > maxVal) {
                maxVal = v
                maxIdx = i
            }
        }
        return maxIdx
    }

    // ── Streaming UTF-8 helper ────────────────────────────────────────────────

    /**
     * Attempts to decode all accumulated bytes in [buffer] as UTF-8.
     *
     * Trailing bytes that begin a multi-byte sequence but are incomplete are held
     * back (kept in [buffer]); the rest are returned as a decoded string.
     */
    private fun flushUtf8(buffer: ByteArrayOutputStream): String {
        if (buffer.size() == 0) return ""
        val bytes = buffer.toByteArray()
        // Scan backwards to find the last complete UTF-8 sequence boundary.
        // The loop intentionally uses `continue` to skip each continuation byte
        // (10xxxxxx) and `break` once a lead byte (11xxxxxx) or ASCII byte is
        // reached. At that point expectedContinuations determines whether the
        // trailing multi-byte sequence is complete; if not, validLen is left at
        // the lead-byte index so those bytes stay buffered. This is not an
        // unconditional jump — the control flow is required to handle the
        // two distinct cases (continuation byte vs. lead/ASCII byte) within a
        // single backwards pass over `bytes`.
        var validLen = bytes.size
        while (validLen > 0) {
            val b = bytes[validLen - 1].toInt() and 0xFF
            // If the last byte is a UTF-8 continuation byte (10xxxxxx), keep trimming
            if (b and 0xC0 == 0x80) {
                validLen--
                continue
            }
            // If it's a lead byte (11xxxxxx), check if its sequence is complete
            val expectedContinuations = when {
                b and 0xE0 == 0xC0 -> 1   // 2-byte sequence needs 1 more
                b and 0xF0 == 0xE0 -> 2   // 3-byte sequence needs 2 more
                b and 0xF8 == 0xF0 -> 3   // 4-byte sequence needs 3 more
                else               -> 0   // ASCII or complete continuation — flush all
            }
            val available = bytes.size - validLen
            if (expectedContinuations > available) {
                // Incomplete sequence — keep this lead byte and continuations buffered
                // validLen stays at the index of this lead byte
            } else {
                validLen = bytes.size // all bytes are valid
            }
            break
        }

        if (validLen <= 0) return ""
        buffer.reset()
        if (validLen < bytes.size) {
            // Re-buffer the trailing incomplete bytes
            buffer.write(bytes, validLen, bytes.size - validLen)
        }
        return String(bytes, 0, validLen, Charsets.UTF_8)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun loadArchConfig() {
        try {
            context.assets.open(CONFIG_ASSET).bufferedReader().use { reader ->
                val json = org.json.JSONObject(reader.readText())
                vocabSize  = json.optInt("vocab_size",           VOCAB_SIZE_DEFAULT)
                numKvHeads = json.optLong("num_key_value_heads", NUM_KV_HEADS_DEFAULT)
                headDim    = json.optLong("head_dim",            HEAD_DIM_DEFAULT)
                Log.d(TAG, "Arch config loaded: vocab=$vocabSize, kvHeads=$numKvHeads, headDim=$headDim")
            }
        } catch (e: Exception) {
            Log.w(TAG, "config.json missing or malformed — using hardcoded arch defaults", e)
        }
    }

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

    private fun createSession(modelPath: String): OrtSession {
        return try {
            val opts = OrtSession.SessionOptions().apply { addNnapi() }
            env.createSession(modelPath, opts)
        } catch (_: Exception) {
            Log.i(TAG, "NNAPI unavailable, falling back to CPU execution provider.")
            env.createSession(modelPath, OrtSession.SessionOptions())
        }
    }

    private fun logSessionInfo() {
        session?.let { sess ->
            Log.d(TAG, "Session inputs:  ${sess.inputInfo.keys.sorted()}")
            Log.d(TAG, "Session outputs: ${sess.outputInfo.keys.sorted()}")
        }
    }

    companion object {
        private const val TAG = "LocalFallbackProvider"
        private const val MODEL_ASSET  = "model_q4.onnx"
        private const val CONFIG_ASSET = "config.json"

        // Only model shards are copied to filesDir — ORT requires real filesystem paths
        // for external-data models. Tokenizer files are read directly from assets.
        private val ASSET_FILES = listOf(
            "model_q4.onnx",
            "model_q4.onnx_data",
            "model_q4.onnx_data_1",
        )

        // ── Llama-3.2-3B architecture defaults ───────────────────────────────
        // Runtime values are parsed from app/src/main/assets/config.json; these
        // constants are used only as fallbacks if that asset is missing or malformed.
        private const val VOCAB_SIZE_DEFAULT    = 128_256
        private const val NUM_KV_HEADS_DEFAULT  = 8L
        private const val HEAD_DIM_DEFAULT      = 128L
        private const val MAX_NEW_TOKENS = 512
    }
}
