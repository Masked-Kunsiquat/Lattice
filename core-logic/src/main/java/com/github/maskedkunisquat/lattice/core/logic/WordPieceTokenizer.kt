package com.github.maskedkunisquat.lattice.core.logic

/**
 * Minimal BERT WordPiece tokenizer for the Snowflake Arctic Embed XS model.
 *
 * Follows the standard BERT tokenization pipeline:
 *   1. Lowercase and strip control characters
 *   2. Insert spaces around CJK characters and punctuation
 *   3. Whitespace-split into words
 *   4. WordPiece-split each word using the bundled vocab
 *   5. Wrap with [CLS] / [SEP] and return input_ids + attention_mask
 *
 * IMPORTANT: Input text must already be PII-masked before calling [encode].
 *
 * @param vocabLines Lines read from vocab.txt, one token per line (0-indexed = token id).
 */
class WordPieceTokenizer(vocabLines: List<String>) {

    private val vocab: Map<String, Int> = buildMap(vocabLines.size) {
        vocabLines.forEachIndexed { i, token -> put(token, i) }
    }

    /**
     * Encodes [text] into (input_ids, attention_mask) LongArrays suitable for ONNX Runtime.
     *
     * The sequence is: [CLS] + tokens + [SEP], truncated to [maxLength] total tokens.
     * No padding is added — the arrays are exactly the sequence length.
     */
    fun encode(text: String, maxLength: Int = MAX_SEQ_LEN): Pair<LongArray, LongArray> {
        val tokens = tokenize(text).take(maxLength - 2) // reserve 2 for [CLS] and [SEP]

        val ids = LongArray(tokens.size + 2)
        ids[0] = CLS_ID.toLong()
        tokens.forEachIndexed { i, token -> ids[i + 1] = vocab.getOrDefault(token, UNK_ID).toLong() }
        ids[ids.size - 1] = SEP_ID.toLong()

        val mask = LongArray(ids.size) { 1L }
        return ids to mask
    }

    // ── Internal pipeline ────────────────────────────────────────────────────

    private fun tokenize(text: String): List<String> =
        basicTokenize(text).flatMap { wordPiece(it) }

    private fun basicTokenize(text: String): List<String> {
        val lower = text.lowercase(java.util.Locale.ROOT)
        val buf = StringBuilder(lower.length * 2)
        var i = 0
        while (i < lower.length) {
            val cp = lower.codePointAt(i)
            val len = Character.charCount(cp)
            when {
                lower[i].isControl() -> buf.append(' ')
                isCjk(cp) || lower[i].isPunct() -> buf.append(' ').append(lower, i, i + len).append(' ')
                else -> buf.append(lower, i, i + len)
            }
            i += len
        }
        return buf.split(whitespaceRegex).filter { it.isNotEmpty() }
    }

    /**
     * Greedy longest-match WordPiece. Returns ["[UNK]"] when no split exists.
     * Words longer than 200 chars are unconditionally mapped to [UNK].
     */
    private fun wordPiece(word: String): List<String> {
        if (word.length > 200) return listOf("[UNK]")
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            val prefix = if (start == 0) "" else "##"
            var end = word.length
            var matched: String? = null
            while (end > start) {
                val candidate = prefix + word.substring(start, end)
                if (candidate in vocab) { matched = candidate; break }
                end--
            }
            matched ?: return listOf("[UNK]")
            pieces += matched
            start = end
        }
        return pieces
    }

    // ── Char helpers ─────────────────────────────────────────────────────────

    private fun Char.isControl(): Boolean {
        val type = Character.getType(this)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    private fun Char.isPunct(): Boolean {
        val c = code
        if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
        val type = Character.getType(this)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt()
    }

    /** CJK Unified Ideographs and the most common extension/compatibility blocks. */
    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF ||
            codePoint in 0x3400..0x4DBF ||
            codePoint in 0x20000..0x2A6DF ||
            codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F ||
            codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x2F800..0x2FA1F

    companion object {
        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val MAX_SEQ_LEN = 512

        private val whitespaceRegex = Regex("\\s+")
    }
}
