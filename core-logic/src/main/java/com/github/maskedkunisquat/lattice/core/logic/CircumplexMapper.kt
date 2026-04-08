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
        val v = valence.coerceIn(-1.0f, 1.0f)
        val a = arousal.coerceIn(-1.0f, 1.0f)
        
        return when {
            v >= 0 && a >= 0 -> {
                if (a > v) MoodLabel.EXCITED else MoodLabel.ALIVE
            }
            v >= 0 && a < 0 -> {
                if (abs(a) > v) MoodLabel.CALM else MoodLabel.SERENE
            }
            v < 0 && a < 0 -> {
                if (abs(a) > abs(v)) MoodLabel.FATIGUED else MoodLabel.DEPRESSED
            }
            v < 0 && a >= 0 -> {
                if (a > abs(v)) MoodLabel.TENSE else MoodLabel.ANGRY
            }
            else -> MoodLabel.ALIVE // Fallback
        }
    }
}
