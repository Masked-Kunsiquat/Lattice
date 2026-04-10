package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.util.regex.Pattern

/**
 * BPE tokenizer for Llama-3.2-3B-Instruct, loaded from the bundled tokenizer.json asset.
 *
 * ## Design
 * - Streams tokenizer.json via [android.util.JsonReader] to avoid DOM allocation overhead
 *   (~11 MB file → ~30 MB runtime footprint for vocab + merge tables).
 * - Pre-tokenisation follows the Llama-3 tiktoken regex, then each piece is byte-level
 *   encoded before BPE merges are applied.
 * - Special tokens (chat-template delimiters like `<|begin_of_text|>`) are detected by
 *   exact substring search and emitted as their own token IDs without passing through BPE.
 *
 * ## Thread safety
 * [initialize] must complete before any [encode]/[decodeToBytes] call. After that the
 * object is read-only and safe for concurrent use.
 */
class LlamaTokenizer(private val context: Context) {

    // token piece → id
    private val vocab = HashMap<String, Int>(140_000)

    // id → token piece (ByteLevel encoded)
    private val idToToken = HashMap<Int, String>(140_000)

    // merge key "left\u0001right" → merge rank (lower = higher priority)
    private val mergeRanks = HashMap<String, Int>(300_000)

    // special tokens: content → id (populated from added_tokens in tokenizer.json)
    private val specialTokens = LinkedHashMap<String, Int>()

    @Volatile private var initialized = false
    private val initLock = Any()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Loads and parses tokenizer.json from assets. No-op on subsequent calls.
     * Must be called once before [encode] or [decodeToBytes].
     */
    fun initialize() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            try {
                context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
                    parseTokenizerJson(JsonReader(reader))
                }
                initialized = true
                Log.d(TAG, "Tokenizer ready — vocab=${vocab.size}, merges=${mergeRanks.size}, specials=${specialTokens.size}")
            } catch (e: Exception) {
                vocab.clear()
                mergeRanks.clear()
                specialTokens.clear()
                idToToken.clear()
                initialized = false
                throw e
            }
        }
    }

    /**
     * Encodes [text] to a sequence of token ids.
     *
     * When [allowSpecialTokens] is true, special chat-template tokens
     * (e.g. `<|begin_of_text|>`) embedded in [text] are resolved directly to their
     * ids; the surrounding normal text is BPE-encoded. Pass true only from trusted
     * scaffold paths (e.g. [LocalFallbackProvider.process]) where the prompt is
     * system-constructed. Leave false (the default) for any user-supplied text to
     * prevent untrusted input from injecting control token ids.
     */
    fun encode(text: String, allowSpecialTokens: Boolean = false): LongArray {
        check(initialized) { "LlamaTokenizer.initialize() must be called first." }
        val ids = mutableListOf<Long>()

        if (!allowSpecialTokens) {
            encodeNormal(text, ids)
            return ids.toLongArray()
        }

        var remaining = text
        while (remaining.isNotEmpty()) {
            // Find the earliest special token in remaining
            var bestIdx = Int.MAX_VALUE
            var bestContent: String? = null
            var bestId = -1
            for ((content, id) in specialTokens) {
                val idx = remaining.indexOf(content)
                if (idx >= 0 && (idx < bestIdx || (idx == bestIdx && content.length > (bestContent?.length ?: -1)))) {
                    bestIdx = idx
                    bestContent = content
                    bestId = id
                }
            }

            if (bestContent == null) {
                // No more special tokens — encode remainder normally
                encodeNormal(remaining, ids)
                break
            }

            // Encode the segment before the special token
            if (bestIdx > 0) encodeNormal(remaining.substring(0, bestIdx), ids)

            // Emit the special token id directly
            ids.add(bestId.toLong())
            remaining = remaining.substring(bestIdx + bestContent.length)
        }

        return ids.toLongArray()
    }

    /**
     * Decodes a single [tokenId] to its raw byte representation.
     *
     * Each character in the BPE vocab piece is a ByteLevel-encoded char; this
     * function maps it back to the original byte value. Callers accumulate bytes
     * across tokens and decode as UTF-8.
     */
    fun decodeToBytes(tokenId: Int): ByteArray {
        check(initialized) { "LlamaTokenizer.initialize() must be called first." }
        val piece = idToToken[tokenId]
            ?: throw IllegalArgumentException("Unknown token id: $tokenId")
        return ByteArray(piece.length) { i ->
            UNICODE_TO_BYTE[piece[i]] ?: run {
                Log.w(TAG, "Unmapped byte-level char '${piece[i]}' in token $tokenId — substituting '?'")
                '?'.code.toByte()
            }
        }
    }

    /** Returns true if [tokenId] signals end of generation. */
    fun isEos(tokenId: Int): Boolean =
        tokenId == EOS_TOKEN_ID || tokenId == EOT_TOKEN_ID || tokenId == EOM_TOKEN_ID

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun encodeNormal(text: String, out: MutableList<Long>) {
        val matcher = PRE_TOKENIZER_PATTERN.matcher(text)
        while (matcher.find()) {
            val piece = matcher.group() ?: continue
            val blPiece = byteLevelEncode(piece)
            bpeEncode(blPiece, out)
        }
    }

    private fun byteLevelEncode(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return String(CharArray(bytes.size) { i ->
            BYTE_TO_UNICODE[bytes[i].toInt() and 0xFF].toChar()
        })
    }

    /**
     * Applies BPE merges to [piece] (a byte-level encoded string) and appends
     * the resulting token ids to [out].
     *
     * Uses the standard priority-queue approach: at each step finds the adjacent
     * pair with the lowest merge rank, merges it, and repeats.
     */
    private fun bpeEncode(piece: String, out: MutableList<Long>) {
        if (piece.isEmpty()) return

        // Fast path: whole piece is in vocab
        val direct = vocab[piece]
        if (direct != null) {
            out.add(direct.toLong())
            return
        }

        // Start with individual chars as symbols
        val symbols = ArrayList<String>(piece.length)
        piece.forEach { symbols.add(it.toString()) }

        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (i in 0 until symbols.size - 1) {
                val rank = mergeRanks["${symbols[i]}\u0001${symbols[i + 1]}"] ?: Int.MAX_VALUE
                if (rank < bestRank) {
                    bestRank = rank
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break

            val merged = symbols[bestIdx] + symbols[bestIdx + 1]
            symbols[bestIdx] = merged
            symbols.removeAt(bestIdx + 1)
        }

        symbols.forEach { sym ->
            val id = vocab[sym]
                ?: throw IllegalArgumentException("BPE symbol '$sym' has no vocab entry — tokenizer may be corrupt")
            out.add(id.toLong())
        }
    }

    // ── JSON parser ───────────────────────────────────────────────────────────

    private fun parseTokenizerJson(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "added_tokens" -> parseAddedTokens(reader)
                "model"        -> parseModel(reader)
                else           -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseAddedTokens(reader: JsonReader) {
        reader.beginArray()
        while (reader.hasNext()) {
            var content: String? = null
            var id = -1
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id"      -> id = reader.nextInt()
                    "content" -> content = reader.nextString()
                    else      -> reader.skipValue()
                }
            }
            reader.endObject()
            if (content != null && id >= 0) {
                specialTokens[content] = id
                // Also add to vocab/idToToken so decoding works
                vocab[content] = id
                idToToken[id] = content
            }
        }
        reader.endArray()
    }

    private fun parseModel(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "vocab"  -> parseVocab(reader)
                "merges" -> parseMerges(reader)
                else     -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun parseVocab(reader: JsonReader) {
        reader.beginObject()
        while (reader.hasNext()) {
            val token = reader.nextName()
            val id = reader.nextInt()
            vocab[token] = id
            idToToken[id] = token
        }
        reader.endObject()
    }

    private fun parseMerges(reader: JsonReader) {
        reader.beginArray()
        var rank = 0
        while (reader.hasNext()) {
            // Llama-3 tokenizer.json stores merges as ["left", "right"] pairs;
            // some older tokenizers use "left right" single strings. Handle both.
            val left: String
            val right: String
            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                left = reader.nextString()
                right = reader.nextString()
                reader.endArray()
            } else {
                val merge = reader.nextString()
                val spaceIdx = merge.indexOf(' ')
                if (spaceIdx <= 0) { rank++; continue }
                left = merge.substring(0, spaceIdx)
                right = merge.substring(spaceIdx + 1)
            }
            mergeRanks["$left\u0001$right"] = rank
            rank++
        }
        reader.endArray()
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "LlamaTokenizer"
        private const val ASSET_NAME = "tokenizer.json"

        const val BOS_TOKEN_ID = 128000  // <|begin_of_text|>
        const val EOS_TOKEN_ID = 128001  // <|end_of_text|>
        const val EOM_TOKEN_ID = 128008  // <|eom_id|>
        const val EOT_TOKEN_ID = 128009  // <|eot_id|>

        /**
         * GPT-2 / Llama-3 byte-to-Unicode table.
         *
         * Bytes 33–126 (!–~), 161–172 (¡–¬), and 174–255 (®–ÿ) map to themselves.
         * The remaining 68 bytes (0–32, 127, 128–160, 173) map to U+0100–U+0143.
         */
        val BYTE_TO_UNICODE: IntArray
        val UNICODE_TO_BYTE: Map<Char, Byte>

        init {
            val b2u = IntArray(256)
            // Bytes that map to themselves
            val selfMapped = HashSet<Int>(200).apply {
                addAll(33 until 127)
                addAll(161 until 173)
                addAll(174 until 256)
            }
            selfMapped.forEach { b -> b2u[b] = b }
            // Remaining bytes map to 256, 257, …
            var n = 0
            for (b in 0 until 256) {
                if (b !in selfMapped) {
                    b2u[b] = 256 + n
                    n++
                }
            }
            BYTE_TO_UNICODE = b2u

            val u2b = HashMap<Char, Byte>(256)
            for (b in 0 until 256) {
                u2b[b2u[b].toChar()] = b.toByte()
            }
            UNICODE_TO_BYTE = u2b
        }

        /**
         * Llama-3 pre-tokenisation regex (from tokenizer.json pre_tokenizer.Split.pattern).
         * Android's ICU-backed regex engine supports \p{L}/\p{N} natively; no extra flags needed.
         */
        private val PRE_TOKENIZER_PATTERN: Pattern = Pattern.compile(
            """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}{1,3}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
        )
    }
}
