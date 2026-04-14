package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.logic.AffectiveManifestStore.fromJson
import com.github.maskedkunisquat.lattice.core.logic.AffectiveManifestStore.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class AffectiveManifestStoreTest {

    // ── toJson ────────────────────────────────────────────────────────────────

    @Test
    fun `toJson produces all six expected keys`() {
        val manifest = AffectiveManifest(
            schemaVersion         = 1,
            baseModelHash         = "sha256:abc123",
            headPath              = "affective_head_v1.bin",
            trainedOnCount        = 42,
            lastTrainingTimestamp = 1_700_000_000_000L,
            baseLayerVersion      = "goEmotions-1.0",
        )
        val obj = JSONObject(manifest.toJson())
        assertEquals(1,                      obj.getInt("schemaVersion"))
        assertEquals("sha256:abc123",        obj.getString("baseModelHash"))
        assertEquals("affective_head_v1.bin",obj.getString("headPath"))
        assertEquals(42,                     obj.getInt("trainedOnCount"))
        assertEquals(1_700_000_000_000L,     obj.getLong("lastTrainingTimestamp"))
        assertEquals("goEmotions-1.0",       obj.getString("baseLayerVersion"))
    }

    @Test
    fun `toJson with zero timestamp emits 0`() {
        val json = AffectiveManifest(lastTrainingTimestamp = 0L).toJson()
        assertEquals(0L, JSONObject(json).getLong("lastTrainingTimestamp"))
    }

    // ── fromJson ──────────────────────────────────────────────────────────────

    @Test
    fun `fromJson round-trips all fields`() {
        val original = AffectiveManifest(
            schemaVersion         = 1,
            baseModelHash         = "sha256:deadbeef",
            headPath              = "affective_head_v1_c030.bin",
            trainedOnCount        = 30,
            lastTrainingTimestamp = 9_999_999_999_999L,
            baseLayerVersion      = "goEmotions-1.0",
        )
        val restored = fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `fromJson uses sensible defaults for missing fields`() {
        val minimal = "{}"
        val manifest = fromJson(minimal)
        assertEquals(1,              manifest.schemaVersion)
        assertEquals("",             manifest.baseModelHash)
        assertEquals("",             manifest.headPath)
        assertEquals(0,              manifest.trainedOnCount)
        assertEquals(0L,             manifest.lastTrainingTimestamp)
        assertEquals("goEmotions-1.0", manifest.baseLayerVersion)
    }

    @Test
    fun `fromJson tolerates extra unknown keys`() {
        val json = """{"schemaVersion":1,"baseModelHash":"sha256:x","headPath":"h.bin",
            |"trainedOnCount":5,"lastTrainingTimestamp":100,"baseLayerVersion":"v2",
            |"futureField":"ignored"}""".trimMargin()
        val manifest = fromJson(json)
        assertEquals(5,    manifest.trainedOnCount)
        assertEquals("v2", manifest.baseLayerVersion)
    }

    @Test(expected = Exception::class)
    fun `fromJson throws on invalid JSON`() {
        fromJson("not-json")
    }

    // ── read (via runCatching guard) ──────────────────────────────────────────

    @Test
    fun `fromJson wrapped in runCatching returns null on invalid JSON`() {
        val result = runCatching { fromJson("not-json") }.getOrNull()
        assertNull(result)
    }

    // ── sha256Hex ─────────────────────────────────────────────────────────────

    @Test
    fun `sha256Hex produces correct digest for known input`() {
        // echo -n "" | sha256sum → e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val emptyHash = sha256Hex(ByteArrayInputStream(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)
    }

    @Test
    fun `sha256Hex is deterministic for same input`() {
        val bytes = ByteArray(512) { it.toByte() }
        val h1 = sha256Hex(ByteArrayInputStream(bytes))
        val h2 = sha256Hex(ByteArrayInputStream(bytes))
        assertEquals(h1, h2)
    }

    @Test
    fun `sha256Hex produces 64-character hex string`() {
        val hash = sha256Hex(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals(64, hash.length)
        assert(hash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Hash must be lowercase hex, got: $hash"
        }
    }

    // ── PREF_KEY constant ─────────────────────────────────────────────────────

    @Test
    fun `PREF_KEY matches roadmap spec`() {
        assertEquals("lattice_affective_manifest", AffectiveManifestStore.PREF_KEY)
    }
}
