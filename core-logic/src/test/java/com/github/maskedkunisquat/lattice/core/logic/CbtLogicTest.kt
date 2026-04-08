package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CbtLogicTest {

    @Test
    fun testDetectDistortions_AllOrNothing() {
        val text = "Everyone always ignores me and I never get what I want."
        val distortions = CbtLogic.detectDistortions(text)
        
        assertTrue("Should detect All-or-Nothing thinking", distortions.contains("All-or-Nothing"))
    }

    @Test
    fun testDetectDistortions_NoDistortions() {
        val text = "I had a productive day today."
        val distortions = CbtLogic.detectDistortions(text)
        
        assertTrue("Should not detect any distortions", distortions.isEmpty())
    }

    @Test
    fun testCircumplexMapper_Clamping() {
        // Test valence clamping (1.5 -> 1.0)
        val highValenceLabel = CircumplexMapper.getLabel(1.5f, 0.5f)
        val expectedHighValenceLabel = CircumplexMapper.getLabel(1.0f, 0.5f)
        assertEquals("1.5 valence should be treated as 1.0", expectedHighValenceLabel, highValenceLabel)

        // Test arousal clamping (-2.0 -> -1.0)
        val lowArousalLabel = CircumplexMapper.getLabel(0.5f, -2.0f)
        val expectedLowArousalLabel = CircumplexMapper.getLabel(0.5f, -1.0f)
        assertEquals("-2.0 arousal should be treated as -1.0", expectedLowArousalLabel, lowArousalLabel)
    }

    @Test
    fun testCircumplexMapper_Bounds() {
        // Verify specific mapping with clamped values
        // Valence 1.5 (clamped to 1.0), Arousal 0.5 -> ALIVE (since valence > arousal)
        assertEquals(MoodLabel.ALIVE, CircumplexMapper.getLabel(1.5f, 0.5f))
        
        // Valence 0.5, Arousal 1.5 (clamped to 1.0) -> EXCITED (since arousal > valence)
        assertEquals(MoodLabel.EXCITED, CircumplexMapper.getLabel(0.5f, 1.5f))
    }
}
