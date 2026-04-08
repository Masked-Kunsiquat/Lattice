package com.github.maskedkunisquat.lattice.core.logic

import kotlin.math.abs

enum class MoodLabel {
    EXCITED, ALIVE, SERENE, CALM, DEPRESSED, FATIGUED, ANGRY, TENSE
}

object CircumplexMapper {
    /**
     * Maps valence and arousal to a MoodLabel based on the Circumplex Model of Affect.
     *
     * @param valence ranges from -1.0 to 1.0 (negative to positive)
     * @param arousal ranges from -1.0 to 1.0 (low to high)
     */
    fun getLabel(valence: Float, arousal: Float): MoodLabel {
        return when {
            valence >= 0 && arousal >= 0 -> {
                if (arousal > valence) MoodLabel.EXCITED else MoodLabel.ALIVE
            }
            valence >= 0 && arousal < 0 -> {
                if (abs(arousal) > valence) MoodLabel.CALM else MoodLabel.SERENE
            }
            valence < 0 && arousal < 0 -> {
                if (abs(arousal) > abs(valence)) MoodLabel.FATIGUED else MoodLabel.DEPRESSED
            }
            valence < 0 && arousal >= 0 -> {
                if (arousal > abs(valence)) MoodLabel.TENSE else MoodLabel.ANGRY
            }
            else -> MoodLabel.ALIVE // Fallback
        }
    }
}
