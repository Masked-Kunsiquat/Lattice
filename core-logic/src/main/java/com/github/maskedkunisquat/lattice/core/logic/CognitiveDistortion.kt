package com.github.maskedkunisquat.lattice.core.logic

/**
 * The 12 cognitive distortions used in the Diagnosis of Thought (DoT) stage.
 *
 * Canonical labels follow the Burns (Feeling Good) taxonomy. [fromLabel] resolves
 * model-output tokens leniently — hyphen/underscore/case variations all match.
 */
enum class CognitiveDistortion(val label: String) {
    ALL_OR_NOTHING("All-or-Nothing"),
    OVERGENERALIZATION("Overgeneralization"),
    MENTAL_FILTER("Mental Filter"),
    DISQUALIFYING_POSITIVE("Disqualifying the Positive"),
    MIND_READING("Mind Reading"),
    FORTUNE_TELLING("Fortune Telling"),
    CATASTROPHIZING("Catastrophizing"),
    EMOTIONAL_REASONING("Emotional Reasoning"),
    SHOULD_STATEMENTS("Should Statements"),
    LABELING("Labeling"),
    PERSONALIZATION("Personalization"),
    BLAME("Blame");

    companion object {
        /** Comma-separated canonical labels for embedding directly in prompts. */
        val promptList: String = entries.joinToString(", ") { it.label }

        /**
         * Resolves [raw] to a [CognitiveDistortion], or null if unrecognised.
         *
         * Normalises hyphens, underscores, and redundant whitespace before comparing
         * so that model variations like "All or Nothing" or "all_or_nothing" both resolve
         * to [ALL_OR_NOTHING].
         */
        fun fromLabel(raw: String): CognitiveDistortion? {
            val normalised = raw.trim()
                .replace(Regex("[-_]+"), " ")
                .replace(Regex("\\s+"), " ")
            return entries.firstOrNull {
                it.label.replace("-", " ").equals(normalised, ignoreCase = true)
            } ?: entries.firstOrNull {
                it.name.replace("_", " ").equals(normalised, ignoreCase = true)
            }
        }
    }
}
