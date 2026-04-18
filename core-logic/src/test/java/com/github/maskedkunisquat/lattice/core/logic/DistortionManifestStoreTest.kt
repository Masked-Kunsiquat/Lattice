package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.logic.DistortionManifestStore.fromJson
import com.github.maskedkunisquat.lattice.core.logic.DistortionManifestStore.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistortionManifestStoreTest {

    // ── toJson ────────────────────────────────────────────────────────────────

    @Test
    fun `toJson produces all expected keys`() {
        val thresholds = FloatArray(12) { 0.4f + it * 0.01f }
        val manifest = DistortionManifest(
            schemaVersion         = 1,
            baseModelHash         = "sha256:abc123",
            headPath              = "distortion_mlp.bin",
            trainedOnCount        = 2530,
            lastTrainingTimestamp = 1_700_000_000_000L,
            corpusVersion         = "v1",
            thresholds            = thresholds,
        )
        val obj = JSONObject(manifest.toJson())
        assertEquals(1,                     obj.getInt("schemaVersion"))
        assertEquals("sha256:abc123",       obj.getString("baseModelHash"))
        assertEquals("distortion_mlp.bin", obj.getString("headPath"))
        assertEquals(2530,                  obj.getInt("trainedOnCount"))
        assertEquals(1_700_000_000_000L,   obj.getLong("lastTrainingTimestamp"))
        assertEquals("v1",                 obj.getString("corpusVersion"))

        val arr = obj.getJSONArray("thresholds")
        assertEquals(12, arr.length())
        for (i in 0 until 12) {
            assertEquals(thresholds[i], arr.getDouble(i).toFloat(), 1e-6f)
        }
    }

    @Test
    fun `toJson zero timestamp emits 0`() {
        val json = DistortionManifest(lastTrainingTimestamp = 0L).toJson()
        assertEquals(0L, JSONObject(json).getLong("lastTrainingTimestamp"))
    }

    // ── fromJson ──────────────────────────────────────────────────────────────

    @Test
    fun `fromJson round-trips all fields`() {
        val original = DistortionManifest(
            schemaVersion         = 1,
            baseModelHash         = "sha256:deadbeef",
            headPath              = "distortion_mlp.bin",
            trainedOnCount        = 3176,
            lastTrainingTimestamp = 9_999_999_999_999L,
            corpusVersion         = "v1",
            thresholds            = FloatArray(12) { 0.3f + it * 0.05f },
        )
        val restored = fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `fromJson uses default thresholds when key absent`() {
        val json = """{"schemaVersion":1,"baseModelHash":"","headPath":"",
            "trainedOnCount":0,"lastTrainingTimestamp":0,"corpusVersion":"v1"}"""
        val manifest = fromJson(json)
        assertEquals(12, manifest.thresholds.size)
        manifest.thresholds.forEach { t ->
            assertEquals(DistortionMlp.DEFAULT_THRESHOLD, t, 1e-6f)
        }
    }

    @Test
    fun `fromJson uses default thresholds when array length is wrong`() {
        val shortArr = org.json.JSONArray().also { repeat(5) { _ -> it.put(0.3) } }
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("thresholds", shortArr)
            .toString()
        val manifest = fromJson(json)
        assertEquals(12, manifest.thresholds.size)
        manifest.thresholds.forEach { t ->
            assertEquals(DistortionMlp.DEFAULT_THRESHOLD, t, 1e-6f)
        }
    }

    @Test
    fun `fromJson uses sensible defaults for all missing fields`() {
        val manifest = fromJson("{}")
        assertEquals(1,    manifest.schemaVersion)
        assertEquals("",   manifest.baseModelHash)
        assertEquals("",   manifest.headPath)
        assertEquals(0,    manifest.trainedOnCount)
        assertEquals(0L,   manifest.lastTrainingTimestamp)
        assertEquals("v1", manifest.corpusVersion)
        assertEquals(12,   manifest.thresholds.size)
    }

    @Test
    fun `fromJson replaces thresholds with defaults when any value overflows to non-finite float`() {
        // 1e39 is a valid JSON double but overflows to Float.POSITIVE_INFINITY on toFloat().
        // The isFinite() guard must catch this and fall back to defaults.
        val arr = org.json.JSONArray().also { a -> repeat(12) { a.put(1.0e39) } }
        val manifest = fromJson(JSONObject().put("thresholds", arr).toString())
        manifest.thresholds.forEach { t ->
            assertEquals(DistortionMlp.DEFAULT_THRESHOLD, t, 1e-6f)
        }
    }

    @Test
    fun `fromJson replaces thresholds with defaults when any value is out of range`() {
        val arr = org.json.JSONArray().also { i ->
            repeat(11) { i.put(0.5) }
            i.put(2.0) // out of [0, 1]
        }
        val manifest = fromJson(JSONObject().put("thresholds", arr).toString())
        manifest.thresholds.forEach { t ->
            assertEquals(DistortionMlp.DEFAULT_THRESHOLD, t, 1e-6f)
        }
    }

    @Test
    fun `fromJson tolerates extra unknown keys`() {
        val json = """{"schemaVersion":1,"baseModelHash":"sha256:x","headPath":"d.bin",
            "trainedOnCount":100,"lastTrainingTimestamp":42,"corpusVersion":"v2",
            "thresholds":[0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5,0.5],
            "futureField":"ignored"}"""
        val manifest = fromJson(json)
        assertEquals(100,  manifest.trainedOnCount)
        assertEquals("v2", manifest.corpusVersion)
    }

    @Test(expected = Exception::class)
    fun `fromJson throws on invalid JSON`() {
        fromJson("not-json")
    }

    @Test
    fun `fromJson wrapped in runCatching returns null on invalid JSON`() {
        val result = runCatching { fromJson("not-json") }.getOrNull()
        assertNull(result)
    }

    // ── DistortionManifest equality ───────────────────────────────────────────

    @Test
    fun `two manifests with identical thresholds are equal`() {
        val a = DistortionManifest(thresholds = FloatArray(12) { 0.4f })
        val b = DistortionManifest(thresholds = FloatArray(12) { 0.4f })
        assertEquals(a, b)
    }

    @Test
    fun `two manifests with different thresholds are not equal`() {
        val a = DistortionManifest(thresholds = FloatArray(12) { 0.4f })
        val b = DistortionManifest(thresholds = FloatArray(12) { 0.5f })
        assertTrue(a != b)
    }

    @Test
    fun `hashCode is consistent with equals for manifests with same thresholds`() {
        val a = DistortionManifest(trainedOnCount = 100, thresholds = FloatArray(12) { 0.45f })
        val b = DistortionManifest(trainedOnCount = 100, thresholds = FloatArray(12) { 0.45f })
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── PREF_KEY constant ─────────────────────────────────────────────────────

    @Test
    fun `PREF_KEY and PREFS_NAME match expected values`() {
        assertEquals("lattice_distortion_manifest", DistortionManifestStore.PREF_KEY)
        assertEquals("lattice_training",            DistortionManifestStore.PREFS_NAME)
    }
}
