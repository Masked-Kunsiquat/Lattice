package com.github.maskedkunisquat.lattice.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistortionCorpusMapperTest {

    // ── map() — happy path ────────────────────────────────────────────────────

    @Test
    fun `map resolves all 10 corpus labels to correct enum values`() {
        val expected = mapOf(
            "All-or-nothing thinking" to CognitiveDistortion.ALL_OR_NOTHING,
            "Overgeneralization"       to CognitiveDistortion.OVERGENERALIZATION,
            "Mental filter"            to CognitiveDistortion.MENTAL_FILTER,
            "Should statements"        to CognitiveDistortion.SHOULD_STATEMENTS,
            "Labeling"                 to CognitiveDistortion.LABELING,
            "Personalization"          to CognitiveDistortion.PERSONALIZATION,
            "Magnification"            to CognitiveDistortion.CATASTROPHIZING,
            "Emotional Reasoning"      to CognitiveDistortion.EMOTIONAL_REASONING,
            "Mind Reading"             to CognitiveDistortion.MIND_READING,
            "Fortune-telling"          to CognitiveDistortion.FORTUNE_TELLING,
        )
        for ((label, distortion) in expected) {
            assertEquals("\"$label\" should map to $distortion",
                distortion, DistortionCorpusMapper.map(label).getOrThrow())
        }
    }

    @Test
    fun `map is case-insensitive`() {
        assertEquals(CognitiveDistortion.LABELING,
            DistortionCorpusMapper.map("LABELING").getOrThrow())
        assertEquals(CognitiveDistortion.MIND_READING,
            DistortionCorpusMapper.map("mind reading").getOrThrow())
    }

    @Test
    fun `map trims surrounding whitespace`() {
        assertEquals(CognitiveDistortion.PERSONALIZATION,
            DistortionCorpusMapper.map("  Personalization  ").getOrThrow())
    }

    @Test
    fun `map returns null for No Distortion`() {
        assertNull(DistortionCorpusMapper.map("No Distortion").getOrThrow())
        assertNull(DistortionCorpusMapper.map("no distortion").getOrThrow())
    }

    // ── map() — unmapped labels ───────────────────────────────────────────────

    @Test
    fun `map returns failure for unrecognised label`() {
        val result = DistortionCorpusMapper.map("Completely Unknown Label")
        assertTrue("unrecognised label must return failure", result.isFailure)
    }

    @Test
    fun `map does not map DISQUALIFYING_POSITIVE from corpus`() {
        // This class has no corpus representation — synthetic data only.
        assertTrue(DistortionCorpusMapper.map("Disqualifying the Positive").isFailure)
    }

    @Test
    fun `map does not map BLAME from corpus`() {
        assertTrue(DistortionCorpusMapper.map("Blame").isFailure)
    }

    // ── toLabels(dominant, secondary) ─────────────────────────────────────────

    @Test
    fun `toLabels sets correct ordinal bit for dominant-only row`() {
        val labels = DistortionCorpusMapper.toLabels("Labeling")!!
        assertTrue("LABELING bit must be set",
            labels[CognitiveDistortion.LABELING.ordinal])
        assertEquals("exactly one bit set", 1, labels.count { it })
    }

    @Test
    fun `toLabels sets both bits for dominant + secondary row`() {
        val labels = DistortionCorpusMapper.toLabels(
            dominant  = "Labeling",
            secondary = "Emotional Reasoning",
        )!!
        assertTrue(labels[CognitiveDistortion.LABELING.ordinal])
        assertTrue(labels[CognitiveDistortion.EMOTIONAL_REASONING.ordinal])
        assertEquals("exactly two bits set", 2, labels.count { it })
    }

    @Test
    fun `toLabels produces all-zeros for No Distortion`() {
        val labels = DistortionCorpusMapper.toLabels("No Distortion")!!
        assertFalse("no bits set for No Distortion", labels.any { it })
    }

    @Test
    fun `toLabels returns null for blank dominant`() {
        assertNull("blank dominant must be dropped", DistortionCorpusMapper.toLabels(""))
        assertNull("blank dominant must be dropped", DistortionCorpusMapper.toLabels("   "))
    }

    @Test
    fun `toLabels returns null for unrecognised dominant`() {
        assertNull("unknown dominant must be dropped",
            DistortionCorpusMapper.toLabels("UnknownDistortionLabel"))
    }

    @Test
    fun `toLabels ignores blank secondary`() {
        val withBlank = DistortionCorpusMapper.toLabels("Personalization", "")!!
        val withNull  = DistortionCorpusMapper.toLabels("Personalization", null)!!
        assertTrue(withBlank.contentEquals(withNull))
        assertEquals(1, withBlank.count { it })
    }

    @Test
    fun `toLabels skips unrecognised secondary without failing the row`() {
        val labels = DistortionCorpusMapper.toLabels("Personalization", "Unknown Label")!!
        assertTrue(labels[CognitiveDistortion.PERSONALIZATION.ordinal])
        assertEquals("only dominant bit should be set", 1, labels.count { it })
    }

    // ── toLabels(CognitiveDistortion?) overload ───────────────────────────────

    @Test
    fun `toLabels(distortion) sets single correct bit`() {
        val labels = DistortionCorpusMapper.toLabels(CognitiveDistortion.FORTUNE_TELLING)
        assertTrue(labels[CognitiveDistortion.FORTUNE_TELLING.ordinal])
        assertEquals(1, labels.count { it })
    }

    @Test
    fun `toLabels(null) produces all-zeros`() {
        assertFalse(DistortionCorpusMapper.toLabels(null).any { it })
    }

    @Test
    fun `toLabels returns array of length equal to enum size`() {
        assertEquals(CognitiveDistortion.entries.size,
            DistortionCorpusMapper.toLabels("Labeling")!!.size)
    }
}
