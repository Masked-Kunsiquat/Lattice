package com.github.maskedkunisquat.lattice.core.logic

import org.json.JSONArray
import org.json.JSONObject

/**
 * Checkpoint manifest for the [DistortionMlp] head, persisted in SharedPreferences
 * under [DistortionManifestStore.PREF_KEY].
 *
 * Written by [DistortionMlpTrainer.save] after each training or threshold-tuning run.
 * Read by [DistortionMlp.load] to verify the embedding model hash and hydrate thresholds.
 *
 * JSON shape:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "baseModelHash": "sha256:<hex>",
 *   "headPath": "distortion_mlp.bin",
 *   "trainedOnCount": 2530,
 *   "lastTrainingTimestamp": 0,
 *   "corpusVersion": "v1",
 *   "thresholds": [0.5, 0.5, ..., 0.5]
 * }
 * ```
 *
 * [thresholds] holds 12 per-class sigmoid thresholds aligned to [CognitiveDistortion.ordinal].
 * They are stored here (not in the weight binary) so threshold tuning after an evaluation
 * sweep can update them without rewriting the full weight file.
 */
data class DistortionManifest(
    val schemaVersion: Int = 1,
    /** SHA-256 digest of the embedding TFLite asset at training time, prefixed "sha256:". */
    val baseModelHash: String = "",
    /** File name of the active weight file under `filesDir`, e.g. "distortion_mlp.bin". */
    val headPath: String = "",
    /** Total number of labeled samples this head was trained on. */
    val trainedOnCount: Int = 0,
    /** Epoch-ms of the most recent training run; 0 if untrained. */
    val lastTrainingTimestamp: Long = 0L,
    /** Corpus version tag — increment when JSONL assets are replaced. */
    val corpusVersion: String = "v1",
    /** Per-class sigmoid thresholds; length must equal [DistortionMlp.OUT2]. */
    val thresholds: FloatArray = FloatArray(DistortionMlp.OUT2) { DistortionMlp.DEFAULT_THRESHOLD },
) {
    // FloatArray equality is reference-based in Kotlin data classes; override for value semantics.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DistortionManifest) return false
        return schemaVersion         == other.schemaVersion &&
            baseModelHash            == other.baseModelHash &&
            headPath                 == other.headPath &&
            trainedOnCount           == other.trainedOnCount &&
            lastTrainingTimestamp    == other.lastTrainingTimestamp &&
            corpusVersion            == other.corpusVersion &&
            thresholds.contentEquals(other.thresholds)
    }

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + baseModelHash.hashCode()
        result = 31 * result + headPath.hashCode()
        result = 31 * result + trainedOnCount
        result = 31 * result + lastTrainingTimestamp.hashCode()
        result = 31 * result + corpusVersion.hashCode()
        result = 31 * result + thresholds.contentHashCode()
        return result
    }
}

/**
 * Reads and writes [DistortionManifest] to/from a [KeyValueStore] as a JSON string.
 *
 * Uses the `"lattice_training"` prefs file so all model manifests are co-located.
 */
object DistortionManifestStore {

    const val PREFS_NAME = "lattice_training"
    const val PREF_KEY   = "lattice_distortion_manifest"

    /**
     * Returns the stored [DistortionManifest], or `null` if none has been written yet
     * or if the stored value cannot be parsed.
     */
    fun read(store: KeyValueStore): DistortionManifest? {
        val raw = store.getString(PREF_KEY) ?: return null
        return runCatching { fromJson(raw) }.getOrNull()
    }

    /**
     * Serialises [manifest] and durably stores it under [PREF_KEY].
     *
     * Callers must use a [KeyValueStore] whose [KeyValueStore.putString] is synchronous
     * (e.g. backed by [android.content.SharedPreferences.Editor.commit]) so that failures
     * are detectable via the return value.
     *
     * @return `true` if the value was written successfully, `false` otherwise.
     */
    fun write(store: KeyValueStore, manifest: DistortionManifest): Boolean =
        store.putString(PREF_KEY, manifest.toJson())

    /**
     * Clears the manifest so [DistortionMlp.load] returns `null` on the next call,
     * forcing retraining on the next training run.
     */
    fun reset(store: KeyValueStore) {
        store.remove(PREF_KEY)
    }

    // ── Serialisation helpers (internal so they can be exercised by unit tests) ──

    internal fun DistortionManifest.toJson(): String {
        val threshArr = JSONArray().also { arr -> thresholds.forEach { arr.put(it.toDouble()) } }
        return JSONObject()
            .put("schemaVersion",          schemaVersion)
            .put("baseModelHash",          baseModelHash)
            .put("headPath",               headPath)
            .put("trainedOnCount",         trainedOnCount)
            .put("lastTrainingTimestamp",  lastTrainingTimestamp)
            .put("corpusVersion",          corpusVersion)
            .put("thresholds",             threshArr)
            .toString()
    }

    internal fun fromJson(json: String): DistortionManifest {
        val obj = JSONObject(json)
        val threshArr = obj.optJSONArray("thresholds")
        val thresholds = if (threshArr != null && threshArr.length() == DistortionMlp.OUT2) {
            val parsed = FloatArray(threshArr.length()) { threshArr.getDouble(it).toFloat() }
            if (parsed.all { it.isFinite() && it >= 0f && it <= 1f }) parsed
            else FloatArray(DistortionMlp.OUT2) { DistortionMlp.DEFAULT_THRESHOLD }
        } else {
            FloatArray(DistortionMlp.OUT2) { DistortionMlp.DEFAULT_THRESHOLD }
        }
        return DistortionManifest(
            schemaVersion         = obj.optInt("schemaVersion", 1),
            baseModelHash         = obj.optString("baseModelHash", ""),
            headPath              = obj.optString("headPath", ""),
            trainedOnCount        = obj.optInt("trainedOnCount", 0),
            lastTrainingTimestamp = obj.optLong("lastTrainingTimestamp", 0L),
            corpusVersion         = obj.optString("corpusVersion", "v1"),
            thresholds            = thresholds,
        )
    }
}
