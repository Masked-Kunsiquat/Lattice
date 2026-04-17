package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the distortion training corpus and serialises it to a binary cache in [Context.filesDir].
 *
 * On first call, reads three JSONL asset files, embeds each text via [EmbeddingProvider],
 * and writes `distortion_dataset_v1.bin` to filesDir so subsequent calls skip re-embedding.
 *
 * Asset paths (relative to `core-logic/src/main/assets/`):
 * - `training/distortion_corpus.jsonl`  — 2 530 rows, 10 Shreevastava classes
 * - `training/distortion_synth_dqp.jsonl` — ~307 synthetic DISQUALIFYING_POSITIVE rows
 * - `training/distortion_synth_blame.jsonl` — ~339 synthetic BLAME rows
 *
 * Binary format of `distortion_dataset_v1.bin` (all values little-endian):
 * ```
 *   [int32]  count       — number of rows
 *   [int32]  dim         — embedding dimension (must equal EmbeddingProvider.EMBEDDING_DIM = 384)
 *   [int32]  numClasses  — number of label classes (must equal CognitiveDistortion.entries.size = 12)
 *   count × row:
 *       [dim × float32]     — embedding vector
 *       [numClasses × byte] — label bits (0 or 1, aligned to CognitiveDistortion ordinals)
 * ```
 *
 * Total cache size ≈ (384 × 4 + 12) × 3 176 ≈ 4.9 MB.
 */
object DistortionDatasetLoader {

    private const val TAG = "DistortionDatasetLoader"
    const val CACHE_FILE = "distortion_dataset_v1.bin"

    private val ASSET_PATHS = listOf(
        "training/distortion_corpus.jsonl",
        "training/distortion_synth_dqp.jsonl",
        "training/distortion_synth_blame.jsonl",
    )

    /**
     * Returns the full distortion training dataset as [DistortionSample] pairs.
     *
     * Reads from the binary cache in [context.filesDir] if it exists; otherwise embeds
     * all JSONL assets via [embeddingProvider] and writes the cache before returning.
     *
     * Caller is responsible for ensuring [embeddingProvider] is already initialised.
     * If [embeddingProvider] is not initialised, rows will be embedded as zero-vectors
     * (EmbeddingProvider's own fallback) and a warning is logged.
     */
    suspend fun load(context: Context, embeddingProvider: EmbeddingProvider): List<DistortionSample> {
        val cacheFile = context.filesDir.resolve(CACHE_FILE)
        if (cacheFile.exists()) {
            Log.i(TAG, "Loading from cache: ${cacheFile.absolutePath}")
            return cacheFile.inputStream().use { deserialize(it) }
        }

        if (!embeddingProvider.isInitialized) {
            Log.w(TAG, "EmbeddingProvider not initialised — embeddings will be zero-vectors")
        }

        Log.i(TAG, "Cache miss — embedding corpus from assets")
        val samples = mutableListOf<DistortionSample>()

        for (assetPath in ASSET_PATHS) {
            val rows = context.assets.open(assetPath).bufferedReader().readLines()
                .filter { it.isNotBlank() }
            Log.i(TAG, "Embedding $assetPath (${rows.size} rows)…")

            for ((idx, line) in rows.withIndex()) {
                val (text, labels) = parseJsonlRow(line)
                val embedding = embeddingProvider.generateEmbedding(text)
                samples += DistortionSample(embedding, labels)
                if ((idx + 1) % 500 == 0) Log.d(TAG, "  …${idx + 1}/${rows.size}")
            }
        }

        Log.i(TAG, "Embedding complete — ${samples.size} total rows. Writing cache…")
        serialize(samples, cacheFile)
        Log.i(TAG, "Cache written to ${cacheFile.absolutePath}")

        return samples
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Writes [samples] to [file] in the binary format described in the class doc.
     * Overwrites any existing file.
     */
    internal fun serialize(samples: List<DistortionSample>, file: File) {
        val dim = EmbeddingProvider.EMBEDDING_DIM
        val numClasses = CognitiveDistortion.entries.size
        val bytesPerRow = dim * Float.SIZE_BYTES + numClasses

        val buf = ByteBuffer
            .allocate(3 * Int.SIZE_BYTES + samples.size * bytesPerRow)
            .order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(samples.size)
        buf.putInt(dim)
        buf.putInt(numClasses)

        for (s in samples) {
            s.embedding.forEach { buf.putFloat(it) }
            s.labels.forEach { buf.put(if (it) 1.toByte() else 0.toByte()) }
        }

        file.writeBytes(buf.array())
    }

    /**
     * Reads [DistortionSample]s from a binary stream produced by [serialize].
     *
     * @throws IllegalArgumentException if the header's dim or numClasses do not match
     *   the current model constants, or if the stream is too short.
     */
    internal fun deserialize(stream: InputStream): List<DistortionSample> {
        val bytes = stream.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val count      = buf.int
        val dim        = buf.int
        val numClasses = buf.int

        require(dim == EmbeddingProvider.EMBEDDING_DIM) {
            "Cache dim=$dim; expected ${EmbeddingProvider.EMBEDDING_DIM}. Delete $CACHE_FILE and re-run."
        }
        require(numClasses == CognitiveDistortion.entries.size) {
            "Cache numClasses=$numClasses; expected ${CognitiveDistortion.entries.size}. Delete $CACHE_FILE and re-run."
        }

        val bytesPerRow = dim * Float.SIZE_BYTES + numClasses
        val expectedBytes = 3L * Int.SIZE_BYTES + count.toLong() * bytesPerRow
        require(bytes.size.toLong() >= expectedBytes) {
            "Cache truncated: ${bytes.size} bytes present, need $expectedBytes for $count rows"
        }

        return List(count) {
            val embedding = FloatArray(dim) { buf.float }
            val labels    = BooleanArray(numClasses) { buf.get() != 0.toByte() }
            DistortionSample(embedding, labels)
        }
    }

    // ── JSONL parsing ─────────────────────────────────────────────────────────

    /**
     * Parses a single JSONL row of the form `{"text": "...", "labels": [false, true, ...]}`.
     *
     * @throws IllegalArgumentException if the row is malformed or the labels array
     *   has the wrong length.
     */
    internal fun parseJsonlRow(line: String): Pair<String, BooleanArray> {
        val obj = JSONObject(line)
        val text = obj.getString("text")

        val jsonLabels: JSONArray = obj.getJSONArray("labels")
        require(jsonLabels.length() == CognitiveDistortion.entries.size) {
            "Expected ${CognitiveDistortion.entries.size} labels, got ${jsonLabels.length()} in: $line"
        }

        val labels = BooleanArray(jsonLabels.length()) { jsonLabels.getBoolean(it) }
        return text to labels
    }
}
