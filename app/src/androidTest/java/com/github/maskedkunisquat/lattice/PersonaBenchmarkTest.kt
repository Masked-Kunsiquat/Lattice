package com.github.maskedkunisquat.lattice

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.data.seed.SeedManager
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona
import com.github.maskedkunisquat.lattice.core.logic.CognitiveDistortion
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LocalFallbackProvider
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.NanoProvider
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import com.github.maskedkunisquat.lattice.core.logic.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG_BENCHMARK = "Lattice:Benchmark"
private const val TAG_INFERENCE = "Lattice:Inference:Logic"
private const val TAG_TELEMETRY = "Lattice:Hardware:Telemetry"
private const val TAG_SETUP    = "Lattice:Benchmark:Setup"

/**
 * Clinical Benchmarking Suite — validates the Unified Agent Loop (Llama-3.2-3B) against
 * three literary personas under Total Air-Gap Privacy.
 *
 * ## What is always asserted (no model required)
 * - Rule of 30: ≥30 journal entries seeded per persona.
 * - PII masking: entry content routed to the LLM contains no raw name variants (firstName/lastName/nickname).
 * - Quadrant routing: the first negative-valence entry maps to the expected [ReframeStrategy].
 * - Sovereignty: [PrivacyLevel.LocalOnly] throughout; zero cloud [TransitEvent]s in the DB.
 * - Cleanup: [SeedManager.clearPersona] leaves a clean manifest.
 *
 * ## What requires the Llama model (gated on [modelLoaded])
 * - Stage 1 (Affective Mapping), Stage 2 (DoT), Stage 3 (Strategic Pivot) succeed.
 * - Stage 3 strategy matches the expected quadrant strategy.
 * - Werther Stage 2 flags [CognitiveDistortion.EMOTIONAL_REASONING].
 *
 * The ONNX model shards (model_q4.onnx + data files) are not committed to VCS. The
 * benchmark does NOT call [LocalFallbackProvider.initialize] in setUp — loading a 3 GB
 * model synchronously would OOM the test process. Instead, [modelLoaded] reflects
 * whatever state [LatticeApplication]'s background init thread has reached by the time
 * the inference block runs. For the CI / no-model path, inference stages are skipped
 * gracefully and only the deterministic assertions execute.
 *
 * To enable full inference benchmarking:
 *   1. Run `./gradlew downloadModels` to fetch model shards into app/src/main/assets/.
 *   2. Re-run the benchmark. LatticeApplication will load the model in its background
 *      thread; [modelLoaded] will be true before the inference block is reached
 *      (provided setUp and seed ingestion give the thread enough lead time).
 */
@RunWith(AndroidJUnit4::class)
class PersonaBenchmarkTest {

    private lateinit var context: Context
    private lateinit var db: LatticeDatabase
    private lateinit var seedManager: SeedManager
    private lateinit var localFallbackProvider: LocalFallbackProvider
    private lateinit var orchestrator: LlmOrchestrator
    private lateinit var reframingLoop: ReframingLoop

    private val seedPrefs
        get() = context.getSharedPreferences("lattice_seed_manager", Context.MODE_PRIVATE)

    /**
     * True only when all ONNX model shards are loaded and the session is ready.
     *
     * The benchmark does NOT call [LocalFallbackProvider.initialize] in setUp — doing so
     * synchronously on the test thread would attempt to load a 3 GB ONNX model, causing
     * an OOM kill after ~25 s. Instead this flag reflects whatever the application's
     * background init thread has already loaded. In CI (no model files) it is always false
     * and inference assertions are skipped cleanly.
     */
    private val modelLoaded: Boolean
        get() = localFallbackProvider.modelLoadState.value == ModelLoadState.READY

    @Before
    fun setUp() {
        Log.i(TAG_SETUP, "setUp: start")
        context = ApplicationProvider.getApplicationContext()
        Log.i(TAG_SETUP, "setUp: context obtained — ${context.javaClass.simpleName}")

        seedPrefs.edit().clear().commit()
        Log.i(TAG_SETUP, "setUp: seedPrefs cleared")

        db = Room.inMemoryDatabaseBuilder(context, LatticeDatabase::class.java).build()
        Log.i(TAG_SETUP, "setUp: in-memory Room DB built")

        seedManager = SeedManager(db, context)
        Log.i(TAG_SETUP, "setUp: SeedManager created")

        // LatticeApplication.onCreate() already called embeddingProvider.initialize(), loading
        // the 23 MB snowflake-arctic-embed-xs model into the native OrtEnvironment singleton.
        // Creating a second session here would crash the process; use the zero-vector fallback.
        val embeddingProvider = EmbeddingProvider()
        Log.i(TAG_SETUP, "setUp: EmbeddingProvider created (no initialize — zero-vector fallback)")

        // Do NOT call localFallbackProvider.initialize() here. Calling it synchronously on the
        // test thread would block until the 3 GB Llama model is loaded (or fail-fast if shards
        // are absent). Either path either OOMs the process or is redundant. The application's
        // background thread handles model loading; modelLoaded reflects that state.
        localFallbackProvider = LocalFallbackProvider(context)
        Log.i(TAG_SETUP, "setUp: LocalFallbackProvider created — modelLoadState=${localFallbackProvider.modelLoadState.value}")

        val searchRepository = SearchRepository(
            journalDao        = db.journalDao(),
            personDao         = db.personDao(),
            embeddingProvider = embeddingProvider,
        )
        Log.i(TAG_SETUP, "setUp: SearchRepository created")

        // Cloud explicitly disabled — sovereignty contract must hold for the entire run.
        orchestrator = LlmOrchestrator(
            nanoProvider          = NanoProvider(context),
            localFallbackProvider = localFallbackProvider,
            transitEventDao       = db.transitEventDao(),
            cloudEnabled          = { false },
        )
        Log.i(TAG_SETUP, "setUp: LlmOrchestrator created — privacyState=${orchestrator.privacyState.value}")

        reframingLoop = ReframingLoop(
            orchestrator         = orchestrator,
            activityHierarchyDao = db.activityHierarchyDao(),
            searchRepository     = searchRepository,
        )
        Log.i(TAG_SETUP, "setUp: ReframingLoop created — modelLoaded=$modelLoaded")
    }

    @After
    fun tearDown() {
        Log.i(TAG_SETUP, "tearDown: closing DB")
        db.close()
        Log.i(TAG_SETUP, "tearDown: done")
    }

    // ── Persona benchmarks ────────────────────────────────────────────────────

    /**
     * Holmes — Quadrant II (v<0, a≥0): expects SOCRATIC_REALITY_TESTING.
     * 35 entries, 30 negative-valence. Seed distortion: Catastrophizing.
     */
    @Test
    fun benchmarkHolmes_Q2_SocraticRealityTesting() {
        runBlocking {
            Log.i(TAG_BENCHMARK, "🚀 Holmes benchmark — Q2 SOCRATIC_REALITY_TESTING")
            runPersonaBenchmark(
                persona          = SeedPersona.HOLMES,
                expectedStrategy = ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            )
            Log.i(TAG_BENCHMARK, "✅ Holmes benchmark complete")
        }
    }

    /**
     * Watson — Quadrant III (v<0, a<0): expects BEHAVIORAL_ACTIVATION.
     * 30 entries, 7 activity hierarchy items. Seed distortion: Overgeneralization.
     */
    @Test
    fun benchmarkWatson_Q3_BehavioralActivation() {
        runBlocking {
            Log.i(TAG_BENCHMARK, "🚀 Watson benchmark — Q3 BEHAVIORAL_ACTIVATION")
            runPersonaBenchmark(
                persona          = SeedPersona.WATSON,
                expectedStrategy = ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            ) {
                val activities = db.activityHierarchyDao().getAllActivities().first()
                assertEquals("Watson must have 7 BA activities seeded", 7, activities.size)
                Log.i(TAG_INFERENCE, "⚖️ Watson activity hierarchy: ${activities.map { it.taskName }}")
            }
            Log.i(TAG_BENCHMARK, "✅ Watson benchmark complete")
        }
    }

    /**
     * Werther — Quadrant II (v<0, a≥0): expects SOCRATIC_REALITY_TESTING.
     * 30 entries, 25 negative-valence. Seed distortions: Emotional Reasoning + Overgeneralization.
     * When the model is loaded, Stage 2 must flag [CognitiveDistortion.EMOTIONAL_REASONING].
     */
    @Test
    fun benchmarkWerther_Stage2_EmotionalReasoning() {
        runBlocking {
            Log.i(TAG_BENCHMARK, "🚀 Werther benchmark — Q2 SOCRATIC_REALITY_TESTING + EMOTIONAL_REASONING DoT")
            runPersonaBenchmark(
                persona          = SeedPersona.WERTHER,
                expectedStrategy = ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            ) { stage2 ->
                if (stage2 != null) {
                    val distortions = stage2.distortions.map { it.label }
                    Log.i(TAG_INFERENCE, "🧠 Werther Stage 2 distortions: $distortions")
                    val flagged = stage2.distortions.contains(CognitiveDistortion.EMOTIONAL_REASONING)
                    Log.i(TAG_INFERENCE, "⚖️ EMOTIONAL_REASONING flagged: $flagged")
                    assertTrue(
                        "Stage 2 must flag EMOTIONAL_REASONING for Werther — got: $distortions",
                        flagged
                    )
                }
            }
            Log.i(TAG_BENCHMARK, "✅ Werther benchmark complete")
        }
    }

    // ── Core benchmark loop ───────────────────────────────────────────────────

    /**
     * Full benchmark sequence for one persona (spec §2, Steps 1–4 + teardown):
     *
     * 1. **Semantic isolation** — `clearAllTables()` + prefs wipe.
     * 2. **Data ingestion** — `seedPersona` from bundled asset; assert Rule of 30.
     * 3. **PII masking** — target entry content must not contain raw name variants (firstName, lastName, fullName, nickname).
     * 4. **Quadrant routing** — first negative-valence entry maps to [expectedStrategy].
     * 5. **Inference** (model-gated) — run Stages 1–3; assert strategy + TTFT telemetry.
     * 6. **Sovereignty** — `PrivacyLevel.LocalOnly` + zero cloud transit events.
     * 7. **Cleanup** — `clearPersona`; manifest count resets to 0.
     *
     * @param personaCheck Optional extra assertions. Receives the [ReframingLoop.DiagnosisResult]
     *   from Stage 2 when the model is loaded, null otherwise, so callers can gate assertions
     *   on model availability without duplicating the check.
     */
    private suspend fun runPersonaBenchmark(
        persona: SeedPersona,
        expectedStrategy: ReframingLoop.ReframeStrategy,
        personaCheck: (suspend (stage2: ReframingLoop.DiagnosisResult?) -> Unit)? = null,
    ) {
        Log.i(TAG_BENCHMARK, "$persona: step 1 — semantic isolation")
        // ── Step 1: Semantic isolation ────────────────────────────────────────
        db.clearAllTables()
        seedPrefs.edit().clear().commit()

        Log.i(TAG_BENCHMARK, "$persona: step 2 — data ingestion")
        // ── Step 2: Data ingestion from bundled asset ─────────────────────────
        // seedPersona(persona) validates Rule of 30, placeholder resolution, and
        // PII-name absence before writing to the DB.
        seedManager.seedPersona(persona)
        val count = seedManager.getSeededEntryCount(persona)
        assertTrue("Rule of 30 not met for $persona: $count entries seeded", count >= 30)
        Log.i(TAG_BENCHMARK, "$persona seeded: $count entries ✓")

        Log.i(TAG_BENCHMARK, "$persona: step 3 — target selection + PII guard")
        // ── Step 3: Target selection + PII masking guard ──────────────────────
        val allEntries = db.journalDao().getAllEntries()
        Log.i(TAG_BENCHMARK, "$persona: getAllEntries returned ${allEntries.size} rows")
        val target = requireNotNull(allEntries.firstOrNull { (it.valence ?: 0f) < 0f }) {
            "No negative-valence entry found for $persona — check seed file valence values"
        }
        val maskedText = requireNotNull(target.content) {
            "Target entry has no text content for $persona"
        }
        Log.i(TAG_BENCHMARK, "$persona: target entry id=${target.id} v=${target.valence} a=${target.arousal}")

        // Belt-and-suspenders: confirm the DB content contains no raw name variants.
        // Hard enforcement happens in SeedManager.validateSeed() at seed time; this
        // catches any regression where content bypasses masking before reaching the LLM.
        Log.i(TAG_BENCHMARK, "$persona: querying persons for PII guard")
        val persons = db.personDao().getPersons().first()
        Log.i(TAG_BENCHMARK, "$persona: PII guard — ${persons.size} person(s) in DB")
        val rawNames = persons.flatMap { p ->
            listOfNotNull(
                p.lastName?.let { "${p.firstName} $it" },
                p.firstName,
                p.lastName,
                p.nickname,
            )
        }
        Log.i(TAG_BENCHMARK, "$persona: PII guard — checking ${rawNames.size} name variant(s): $rawNames")
        rawNames.forEach { name ->
            // Mirror SeedManager.validateNoRawNames: word-boundary anchor prevents
            // short surnames like "H." from matching mid-word (e.g. "reach.", "each.").
            val pattern = Regex("(?i)\\b${Regex.escape(name)}")
            assertFalse(
                "$persona: raw name '$name' found unmasked in entry content — PII leak",
                pattern.containsMatchIn(maskedText)
            )
        }
        Log.i(TAG_INFERENCE, "🧠 $persona PII guard passed — no raw names in target content")

        Log.i(TAG_BENCHMARK, "$persona: step 4 — quadrant routing")
        // ── Step 4: Quadrant routing (deterministic) ──────────────────────────
        val computedStrategy = ReframingLoop.selectStrategy(
            valence = target.valence ?: 0f,
            arousal = target.arousal ?: 0f,
        )
        assertEquals("$persona quadrant strategy mismatch", expectedStrategy, computedStrategy)
        Log.i(TAG_INFERENCE, "⚖️ $persona quadrant strategy: $computedStrategy ✓")

        Log.i(TAG_BENCHMARK, "$persona: step 5 — inference (modelLoaded=$modelLoaded)")
        // ── Step 5: Inference (gated on model availability) ───────────────────
        var stage2Result: ReframingLoop.DiagnosisResult? = null

        if (modelLoaded) {
            Log.i(TAG_BENCHMARK, "🚀 $persona — Llama ONNX inference starting (NNAPI requested)")

            val t0 = System.currentTimeMillis()

            // Stage 1 — Affective Mapping
            val stage1 = reframingLoop.runStage1AffectiveMap(maskedText)
            val ttft = System.currentTimeMillis() - t0
            assertTrue(
                "$persona Stage 1 (affective map) failed: ${stage1.exceptionOrNull()?.message}",
                stage1.isSuccess
            )
            Log.i(TAG_INFERENCE, "🧠 $persona Stage 1: label=${stage1.getOrNull()?.label} (${ttft}ms TTFT)")

            // Stage 2 — Diagnosis of Thought
            val t1 = System.currentTimeMillis()
            val stage2 = reframingLoop.runStage2DiagnosisOfThought(maskedText)
            assertTrue(
                "$persona Stage 2 (DoT) failed: ${stage2.exceptionOrNull()?.message}",
                stage2.isSuccess
            )
            stage2Result = stage2.getOrNull()
            Log.i(TAG_INFERENCE, "🧠 $persona Stage 2 distortions: ${stage2Result?.distortions?.map { it.label }}")

            // Stage 3 — Strategic Pivot
            val stage3 = reframingLoop.runStage3Intervention(
                maskedText   = maskedText,
                affectiveMap = stage1.getOrThrow(),
                diagnosis    = stage2.getOrThrow(),
            )
            val totalMs = System.currentTimeMillis() - t1
            assertTrue(
                "$persona Stage 3 (intervention) failed: ${stage3.exceptionOrNull()?.message}",
                stage3.isSuccess
            )
            assertEquals(
                "$persona Stage 3 strategy must match expected quadrant",
                expectedStrategy, stage3.getOrThrow().strategy
            )
            Log.i(
                TAG_TELEMETRY,
                "📊 $persona · TTFT=${ttft}ms · Stage2+3=${totalMs}ms · " +
                "strategy=${stage3.getOrThrow().strategy} · provider=${localFallbackProvider.id}"
            )
        } else {
            Log.w(
                TAG_BENCHMARK,
                "$persona — Llama ONNX model not loaded (modelLoadState=${localFallbackProvider.modelLoadState.value}); " +
                "inference stages skipped. Bundle model_q4.onnx + data shards into " +
                "app/src/main/assets/ and re-run to enable full benchmark execution."
            )
        }

        // ── Persona-specific extra assertions ─────────────────────────────────
        personaCheck?.invoke(stage2Result)

        Log.i(TAG_BENCHMARK, "$persona: step 6 — sovereignty check")
        // ── Step 6: Sovereignty check — 0% cloud transit ─────────────────────
        assertEquals(
            "$persona: privacyState must be LocalOnly (no cloud routing occurred)",
            PrivacyLevel.LocalOnly,
            orchestrator.privacyState.value
        )
        val cloudEvents = db.transitEventDao().getAllEvents()
            .filterNot { it.providerName == "seed_injection" }
        assertTrue(
            "$persona: unexpected non-seed transit events found — cloud data leak: $cloudEvents",
            cloudEvents.isEmpty()
        )
        Log.i(TAG_BENCHMARK, "$persona sovereignty: LocalOnly ✓, cloud transit events=${cloudEvents.size}")

        Log.i(TAG_BENCHMARK, "$persona: step 7 — cleanup")
        // ── Step 7: Cleanup ───────────────────────────────────────────────────
        seedManager.clearPersona(persona)
        assertEquals(
            "$persona manifest must be cleared after benchmark",
            0, seedManager.getSeededEntryCount(persona)
        )
        Log.i(TAG_BENCHMARK, "$persona: benchmark sequence complete ✓")
    }
}
