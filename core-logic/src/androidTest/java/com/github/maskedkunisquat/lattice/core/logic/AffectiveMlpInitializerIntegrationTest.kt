package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end warm-start integration test for [AffectiveMlpInitializer].
 *
 * Requires a device/emulator with the production assets present (the GoEmotions base asset and
 * `snowflake-arctic-embed-xs.onnx` are both committed to `:core-logic/src/main/assets`).
 *
 * [Dispatchers.Unconfined] is injected so the coroutine launched inside [AffectiveMlpInitializer.maybeInitialize]
 * runs synchronously in the test thread — the body contains no suspension points, so the entire
 * load → train → save → manifest sequence completes before the assertion block is reached.
 */
@RunWith(AndroidJUnit4::class)
class AffectiveMlpInitializerIntegrationTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        scope = CoroutineScope(Dispatchers.Unconfined)
        // Guarantee a clean slate so each test starts from first-launch state.
        AffectiveManifestStore.resetAll(prefs)
        context.filesDir.resolve(AffectiveMlpInitializer.WEIGHT_FILE).delete()
    }

    @After
    fun tearDown() {
        scope.cancel()
        AffectiveManifestStore.resetAll(prefs)
        context.filesDir.resolve(AffectiveMlpInitializer.WEIGHT_FILE).delete()
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    /**
     * Full warm-start sequence:
     * load asset → trainBatch → save weights → write manifest → guard flag set.
     */
    @Test
    fun warmStart_writesWeightFile_manifestAndGuardFlag() {
        val initializer = AffectiveMlpInitializer(Dispatchers.Unconfined)
        val mlp = AffectiveMlp()

        initializer.maybeInitialize(context, mlp, scope)

        // Weight file must exist with the exact expected byte size
        val weightFile = context.filesDir.resolve(AffectiveMlpInitializer.WEIGHT_FILE)
        assertTrue("Weight file must exist after warm-start", weightFile.exists())
        assertEquals(
            "Weight file must be exactly WEIGHT_BYTES in size",
            AffectiveMlp.WEIGHT_BYTES.toLong(),
            weightFile.length(),
        )

        // Guard flag must be set so subsequent launches skip training
        assertTrue(
            "SharedPreferences guard '${AffectiveMlpInitializer.PREF_KEY}' must be true after warm-start",
            prefs.getBoolean(AffectiveMlpInitializer.PREF_KEY, false),
        )

        // Manifest must be present and internally consistent
        val manifest = AffectiveManifestStore.read(prefs)
        assertNotNull("Manifest must be written to SharedPreferences", manifest)
        checkNotNull(manifest)

        assertTrue("trainedOnCount must be > 0", manifest.trainedOnCount > 0)
        assertEquals(
            "headPath must equal WEIGHT_FILE constant",
            AffectiveMlpInitializer.WEIGHT_FILE,
            manifest.headPath,
        )
        assertEquals(
            "baseLayerVersion must equal BASE_LAYER_VERSION constant",
            AffectiveMlpInitializer.BASE_LAYER_VERSION,
            manifest.baseLayerVersion,
        )
        assertTrue(
            "baseModelHash must start with 'sha256:'",
            manifest.baseModelHash.startsWith("sha256:"),
        )
        assertTrue(
            "lastTrainingTimestamp must be a positive epoch-ms value",
            manifest.lastTrainingTimestamp > 0L,
        )
    }

    // ── No-op guard ───────────────────────────────────────────────────────────

    /**
     * A second [AffectiveMlpInitializer.maybeInitialize] call must be a no-op once the
     * guard flag has been set: weight file and manifest must remain unchanged.
     */
    @Test
    fun secondMaybeInitialize_isNoOp_whenGuardFlagSet() {
        val initializer = AffectiveMlpInitializer(Dispatchers.Unconfined)

        // First call — performs warm-start
        initializer.maybeInitialize(context, AffectiveMlp(), scope)

        val weightFile = context.filesDir.resolve(AffectiveMlpInitializer.WEIGHT_FILE)
        val timestampAfterFirst = weightFile.lastModified()
        val manifestAfterFirst  = AffectiveManifestStore.read(prefs)

        // Brief pause so that a second write would produce a different lastModified value.
        Thread.sleep(50)

        // Second call with a fresh MLP — guard flag is already set, so nothing should happen
        initializer.maybeInitialize(context, AffectiveMlp(), scope)

        assertEquals(
            "Weight file must not be re-written on second maybeInitialize call",
            timestampAfterFirst,
            weightFile.lastModified(),
        )
        assertEquals(
            "Manifest must not change on second maybeInitialize call",
            manifestAfterFirst,
            AffectiveManifestStore.read(prefs),
        )
    }

    // ── Saved weights are loadable ────────────────────────────────────────────

    /**
     * Weights saved by warm-start must round-trip cleanly through [AffectiveMlp.loadWeights]
     * and produce finite (v, a) output for a synthetic embedding.
     */
    @Test
    fun warmStart_savedWeights_areLoadableAndProduceFiniteOutput() {
        val initializer = AffectiveMlpInitializer(Dispatchers.Unconfined)
        initializer.maybeInitialize(context, AffectiveMlp(), scope)

        val weightFile = context.filesDir.resolve(AffectiveMlpInitializer.WEIGHT_FILE)
        val loaded = AffectiveMlp.loadWeights(weightFile)

        val testEmbedding = FloatArray(AffectiveMlp.IN) { 0.1f }
        val (v, a) = loaded.forward(testEmbedding)
        assertTrue("valence must be finite after loading warm-started weights", v.isFinite())
        assertTrue("arousal must be finite after loading warm-started weights", a.isFinite())
        assertTrue("valence must be in [-1, 1]", v in -1f..1f)
        assertTrue("arousal must be in [-1, 1]", a in -1f..1f)
    }
}
