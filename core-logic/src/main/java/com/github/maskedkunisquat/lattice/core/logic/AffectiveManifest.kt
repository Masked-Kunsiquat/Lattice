package com.github.maskedkunisquat.lattice.core.logic

import android.content.SharedPreferences
import com.github.maskedkunisquat.lattice.core.data.model.TrainingManifestEntity
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * Checkpoint manifest persisted in SharedPreferences under [AffectiveManifestStore.PREF_KEY].
 *
 * Written by [AffectiveMlpInitializer] after the GoEmotions base warm-start and by
 * `EmbeddingTrainingWorker` after each fine-tuning cycle. Read by [AffectiveMlp.load]
 * to verify that the embedding model has not been replaced since the head was trained.
 *
 * JSON shape:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "baseModelHash": "sha256:<hex>",
 *   "headPath": "affective_head_v1.bin",
 *   "trainedOnCount": 30,
 *   "lastTrainingTimestamp": 0,
 *   "baseLayerVersion": "goEmotions-1.0"
 * }
 * ```
 */
data class AffectiveManifest(
    val schemaVersion: Int = 1,
    /** SHA-256 digest of `snowflake-arctic-embed-xs.onnx` at training time, prefixed "sha256:". */
    val baseModelHash: String = "",
    /** File name of the active weight file under `filesDir`, e.g. "affective_head_v1.bin". */
    val headPath: String = "",
    /** Total number of user-labeled samples this head has been trained on. */
    val trainedOnCount: Int = 0,
    /** Epoch-ms of the most recent training cycle; 0 if only the base warm-start has run. */
    val lastTrainingTimestamp: Long = 0L,
    /** Version tag of the GoEmotions base asset used for warm-start. */
    val baseLayerVersion: String = "goEmotions-1.0",
)

/**
 * Reads and writes [AffectiveManifest] to/from SharedPreferences as a JSON string.
 *
 * Uses the `"lattice_training"` prefs file so the manifest and the
 * [AffectiveMlpInitializer] guard flag are co-located.
 */
object AffectiveManifestStore {

    const val PREFS_NAME = "lattice_training"
    const val PREF_KEY   = "lattice_affective_manifest"

    /**
     * Returns the stored [AffectiveManifest], or `null` if none has been written yet
     * or if the stored value cannot be parsed.
     */
    fun read(prefs: SharedPreferences): AffectiveManifest? {
        val raw = prefs.getString(PREF_KEY, null) ?: return null
        return runCatching { fromJson(raw) }.getOrNull()
    }

    /**
     * Serialises [manifest] and durably stores it under [PREF_KEY].
     *
     * Uses [SharedPreferences.Editor.commit] (synchronous, returns success/failure)
     * rather than [SharedPreferences.Editor.apply] (fire-and-forget) so callers can
     * detect a failed write and react — e.g., by clearing the warm-start guard so the
     * next launch retries rather than silently skipping initialisation.
     *
     * @return `true` if the value was written successfully, `false` otherwise.
     */
    fun write(prefs: SharedPreferences, manifest: AffectiveManifest): Boolean =
        prefs.edit().putString(PREF_KEY, manifest.toJson()).commit()

    /**
     * Clears both the manifest ([PREF_KEY]) and the [AffectiveMlpInitializer] guard flag
     * so warm-start re-runs on next launch.
     *
     * Centralises the eviction logic here so that renaming either key does not silently
     * break the reset path in [AffectiveMlp.load].
     */
    fun resetAll(prefs: SharedPreferences) {
        prefs.edit()
            .remove(PREF_KEY)
            .remove(AffectiveMlpInitializer.PREF_KEY)
            .apply()
    }

    // ── Serialisation helpers (internal so they can be exercised by unit tests) ──

    internal fun AffectiveManifest.toJson(): String = JSONObject()
        .put("schemaVersion",         schemaVersion)
        .put("baseModelHash",         baseModelHash)
        .put("headPath",              headPath)
        .put("trainedOnCount",        trainedOnCount)
        .put("lastTrainingTimestamp", lastTrainingTimestamp)
        .put("baseLayerVersion",      baseLayerVersion)
        .toString()

    internal fun fromJson(json: String): AffectiveManifest {
        val obj = JSONObject(json)
        return AffectiveManifest(
            schemaVersion         = obj.optInt("schemaVersion", 1),
            baseModelHash         = obj.optString("baseModelHash", ""),
            headPath              = obj.optString("headPath", ""),
            trainedOnCount        = obj.optInt("trainedOnCount", 0),
            lastTrainingTimestamp = obj.optLong("lastTrainingTimestamp", 0L),
            baseLayerVersion      = obj.optString("baseLayerVersion", "goEmotions-1.0"),
        )
    }
}

// ── Room ↔ domain mapping ─────────────────────────────────────────────────────

/** Maps a [TrainingManifestEntity] to its [AffectiveManifest] domain representation. */
fun TrainingManifestEntity.toAffectiveManifest() = AffectiveManifest(
    schemaVersion         = schemaVersion,
    baseModelHash         = baseModelHash,
    headPath              = headPath,
    trainedOnCount        = trainedOnCount,
    lastTrainingTimestamp = lastTrainingTimestamp,
    baseLayerVersion      = baseLayerVersion,
)

/** Maps an [AffectiveManifest] to a [TrainingManifestEntity] for Room persistence. */
fun AffectiveManifest.toEntity() = TrainingManifestEntity(
    schemaVersion         = schemaVersion,
    baseModelHash         = baseModelHash,
    headPath              = headPath,
    trainedOnCount        = trainedOnCount,
    lastTrainingTimestamp = lastTrainingTimestamp,
    baseLayerVersion      = baseLayerVersion,
)

/**
 * Computes the SHA-256 digest of [stream] and returns it as a lowercase hex string.
 *
 * The stream is consumed but **not** closed by this function.
 */
internal fun sha256Hex(stream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8192)
    var read: Int
    while (stream.read(buf).also { read = it } != -1) {
        digest.update(buf, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
