package com.github.maskedkunisquat.lattice.core.logic

import android.util.Log

/**
 * Maps Shreevastava et al. corpus label strings to [CognitiveDistortion] enum values.
 *
 * The corpus covers 10 of the 12 distortions. [CognitiveDistortion.DISQUALIFYING_POSITIVE]
 * and [CognitiveDistortion.BLAME] are absent and must be supplied via synthetic data.
 *
 * "No Distortion" rows map to null (→ all-zeros label vector), which trains the model to
 * output an empty set rather than always firing at least one class.
 *
 * CSV columns consumed: `Dominant Distortion`, `Secondary Distortion (Optional)`.
 */
object DistortionCorpusMapper {

    private const val TAG = "DistortionCorpusMapper"

    /** Explicit corpus-label → enum mapping (normalised to lowercase for lookup). */
    private val CORPUS_MAP: Map<String, CognitiveDistortion?> = mapOf(
        "all-or-nothing thinking" to CognitiveDistortion.ALL_OR_NOTHING,
        "overgeneralization"       to CognitiveDistortion.OVERGENERALIZATION,
        "mental filter"            to CognitiveDistortion.MENTAL_FILTER,
        "should statements"        to CognitiveDistortion.SHOULD_STATEMENTS,
        "labeling"                 to CognitiveDistortion.LABELING,
        "personalization"          to CognitiveDistortion.PERSONALIZATION,
        "magnification"            to CognitiveDistortion.CATASTROPHIZING,
        "emotional reasoning"      to CognitiveDistortion.EMOTIONAL_REASONING,
        "mind reading"             to CognitiveDistortion.MIND_READING,
        "fortune-telling"          to CognitiveDistortion.FORTUNE_TELLING,
        "no distortion"            to null,
    )

    /**
     * Maps a single raw corpus label to a [CognitiveDistortion], or null for "No Distortion".
     *
     * Returns [Result.failure] with a warning log when the label is unrecognised — the caller
     * should drop the row rather than propagating the error.
     */
    fun map(raw: String): Result<CognitiveDistortion?> {
        val key = raw.trim().lowercase()
        return if (CORPUS_MAP.containsKey(key)) {
            Result.success(CORPUS_MAP[key])
        } else {
            Log.w(TAG, "Unmapped corpus label dropped: \"$raw\"")
            Result.failure(IllegalArgumentException("Unmapped corpus label: \"$raw\""))
        }
    }

    /**
     * Builds a 12-element [BooleanArray] aligned to [CognitiveDistortion.entries] ordinal order
     * from [dominant] and an optional [secondary] label string.
     *
     * Unrecognised labels are logged and skipped; the row is not dropped entirely so that a
     * valid dominant label still produces a useful training example.
     */
    fun toLabels(dominant: String, secondary: String? = null): BooleanArray {
        val labels = BooleanArray(CognitiveDistortion.entries.size)
        for (raw in listOfNotNull(dominant.takeIf { it.isNotBlank() }, secondary?.takeIf { it.isNotBlank() })) {
            map(raw).getOrNull()?.let { labels[it.ordinal] = true }
        }
        return labels
    }

    /**
     * Converts a single [CognitiveDistortion] (or null for no-distortion) to a
     * 12-element [BooleanArray]. Convenience overload used by synthetic data loaders.
     */
    fun toLabels(distortion: CognitiveDistortion?): BooleanArray {
        val labels = BooleanArray(CognitiveDistortion.entries.size)
        if (distortion != null) labels[distortion.ordinal] = true
        return labels
    }
}
