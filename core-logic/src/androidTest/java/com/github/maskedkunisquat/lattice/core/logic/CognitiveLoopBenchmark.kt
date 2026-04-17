package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.data.seed.SeedManager
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end performance benchmarks for the three-stage CBT reframing pipeline.
 *
 * ## Device requirement
 * Requires the Gemma 3 1B LiteRT model file (`gemma3-1b-it-s25.litertlm`) to be
 * present in the test APK's assets. The file is gitignored — fetch it with
 * `./gradlew downloadModels`. The entire class is skipped via [assumeTrue] when
 * the model is absent, so it runs cleanly on emulators and CI without modification.
 *
 * ## Personas
 * - **Holmes** — Q2 (v<0, a≥0): triggers [ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING]
 * - **Watson** — Q3 (v<0, a<0): triggers [ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION]
 *   Watson's activity hierarchy (7 entries) is seeded so the BA lookup has real data to evaluate.
 *
 * ## Measured tests
 * | Test | What is timed |
 * |---|---|
 * | `stage1_*` | Affective Mapping — model output is short (`v=X a=Y`) |
 * | `stage2_*` | Diagnosis of Thought — longest output (chain-of-thought + sentinel) |
 * | `stage3_*` | Strategic Pivot — S1+S2 pre-computed; measures intervention cost only |
 * | `ttft_*`   | Time-to-first-token from [ReframingLoop.streamStage3Intervention] call |
 * | `memoryDelta_fullLoop_*` | PSS Δ across a full 3-stage run; logged via [Log.i] |
 *
 * ## Setup amortisation
 * Expensive one-time work (model load, DB seed, S1+S2 pre-computation) is cached in
 * companion object fields and skipped on the second and subsequent [setUp] calls.
 * The [BenchmarkRule] runs warmup + measurement iterations per test, not per class.
 */
@RunWith(AndroidJUnit4::class)
class CognitiveLoopBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    companion object {
        private const val TAG = "CognitiveLoopBenchmark"

        // Representative masked texts — real content from the seed files.
        // Holmes: Q2 (v=-0.4, a=0.7) → SOCRATIC_REALITY_TESTING
        private const val HOLMES_TEXT =
            "The entire investigation has collapsed. Every lead I followed was " +
            "worthless and the outcome is an absolute catastrophe."

        // Watson: Q3 (v=-0.5, a=-0.6) → BEHAVIORAL_ACTIVATION
        private const val WATSON_TEXT =
            "I cancelled the plans I had made for today. It seemed easier to " +
            "stay home than to face the effort of going out."

        // Shared across all test methods — model loading is too expensive to repeat.
        @Volatile private var db: LatticeDatabase? = null
        @Volatile private var loop: ReframingLoop? = null

        // Pre-computed Stage 1 & 2 outputs — isolate Stage 3 / TTFT from upstream latency.
        @Volatile private var holmesAffective: ReframingLoop.AffectiveMapResult? = null
        @Volatile private var holmesDiagnosis: ReframingLoop.DiagnosisResult? = null
        @Volatile private var watsonAffective: ReframingLoop.AffectiveMapResult? = null
        @Volatile private var watsonDiagnosis: ReframingLoop.DiagnosisResult? = null
    }

    @Before
    fun setUp() = runBlocking {
        // Already initialized — skip expensive setup on subsequent test methods.
        if (loop != null) return@runBlocking

        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initialize model — blocks until shards are loaded or fails silently.
        val provider = LocalFallbackProvider(context)
        provider.initialize()

        // Gate: skip the entire class when Llama shards are absent.
        // Shards live in app/src/main/assets/ (gitignored). Symlink or copy them to
        // core-logic/src/androidTest/assets/ to enable this benchmark locally.
        assumeTrue(
            "Gemma 3 1B model not loaded " +
            "(state=${provider.modelLoadState.value}). " +
            "Run ./gradlew downloadModels to fetch gemma3-1b-it-s25.litertlm into app/src/main/assets/.",
            provider.modelLoadState.value == ModelLoadState.READY
        )

        val localDb = Room.inMemoryDatabaseBuilder(context, LatticeDatabase::class.java).build()
        val seedManager = SeedManager(localDb, context)
        SeedPersona.entries.forEach { seedManager.seedPersona(it) }
        db = localDb

        val embeddingProvider = EmbeddingProvider().also { it.initialize(context) }
        val searchRepository = SearchRepository(
            journalDao = localDb.journalDao(),
            personDao = localDb.personDao(),
            embeddingProvider = embeddingProvider,
        )

        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano_stub"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = localDb.transitEventDao(),
            cloudEnabled = { false },
        )

        val reframingLoop = ReframingLoop(
            orchestrator = orchestrator,
            activityHierarchyDao = localDb.activityHierarchyDao(),
            searchRepository = searchRepository,
        )

        // Pre-run Stages 1 and 2 for both personas so Stage 3 and TTFT benchmarks
        // reflect only intervention inference cost, not upstream stage latency.
        holmesAffective = reframingLoop.runStage1AffectiveMap(HOLMES_TEXT).getOrThrow()
        holmesDiagnosis = reframingLoop.runStage2DiagnosisOfThought(HOLMES_TEXT).getOrThrow()
        watsonAffective = reframingLoop.runStage1AffectiveMap(WATSON_TEXT).getOrThrow()
        watsonDiagnosis = reframingLoop.runStage2DiagnosisOfThought(WATSON_TEXT).getOrThrow()

        loop = reframingLoop
    }

    @After
    fun tearDown() {
        // Intentionally no-op — DB and provider are reused across test methods.
        // The test runner terminates the process when the class is done.
    }

    // ── Stage 1: Affective Mapping ────────────────────────────────────────────
    // Output is short ("v=X a=Y") — this is the fastest stage per token count.

    @Test
    fun stage1_holmes() {
        val l = loop!!
        benchmarkRule.measureRepeated {
            runBlocking { l.runStage1AffectiveMap(HOLMES_TEXT).getOrThrow() }
        }
    }

    @Test
    fun stage1_watson() {
        val l = loop!!
        benchmarkRule.measureRepeated {
            runBlocking { l.runStage1AffectiveMap(WATSON_TEXT).getOrThrow() }
        }
    }

    // ── Stage 2: Diagnosis of Thought ────────────────────────────────────────
    // Longest output per run — full chain-of-thought reasoning + DISTORTIONS sentinel.

    @Test
    fun stage2_holmes() {
        assumeTrue(
            "Pass -e enableSlowBenchmarks true to enable this slow benchmark.",
            InstrumentationRegistry.getArguments().getString("enableSlowBenchmarks") != null,
        )
        val l = loop!!
        benchmarkRule.measureRepeated {
            runBlocking { l.runStage2DiagnosisOfThought(HOLMES_TEXT).getOrThrow() }
        }
    }

    @Test
    fun stage2_watson() {
        val l = loop!!
        benchmarkRule.measureRepeated {
            runBlocking { l.runStage2DiagnosisOfThought(WATSON_TEXT).getOrThrow() }
        }
    }

    // ── Stage 3: Strategic Pivot ──────────────────────────────────────────────
    // S1+S2 pre-computed in setUp(); measures only the intervention inference cost.
    // Holmes → SOCRATIC_REALITY_TESTING; Watson → BEHAVIORAL_ACTIVATION (+ BA lookup
    // + evidence fetch from seeded DB).

    @Test
    fun stage3_holmes() {
        val l = loop!!
        val affective = holmesAffective!!
        val diagnosis = holmesDiagnosis!!
        benchmarkRule.measureRepeated {
            runBlocking { l.runStage3Intervention(HOLMES_TEXT, affective, diagnosis).getOrThrow() }
        }
    }

    @Test
    fun stage3_watson() {
        val l = loop!!
        val affective = watsonAffective!!
        val diagnosis = watsonDiagnosis!!
        benchmarkRule.measureRepeated {
            runBlocking { l.runStage3Intervention(WATSON_TEXT, affective, diagnosis).getOrThrow() }
        }
    }

    // ── TTFT: Time-to-first-token ─────────────────────────────────────────────
    // Measures from streamStage3Intervention() call — including prep (strategy
    // selection, BA lookup, evidence fetch) — to the arrival of the first
    // LlmResult.Token from the model. This is the latency a user perceives before
    // any text appears in the reframe card.

    @Test
    fun ttft_holmes() {
        val l = loop!!
        val affective = holmesAffective!!
        val diagnosis = holmesDiagnosis!!
        benchmarkRule.measureRepeated {
            runBlocking {
                val (_, flow) = l.streamStage3Intervention(HOLMES_TEXT, affective, diagnosis)
                    .getOrThrow()
                flow.first { it is LlmResult.Token }
            }
        }
    }

    @Test
    fun ttft_watson() {
        val l = loop!!
        val affective = watsonAffective!!
        val diagnosis = watsonDiagnosis!!
        benchmarkRule.measureRepeated {
            runBlocking {
                val (_, flow) = l.streamStage3Intervention(WATSON_TEXT, affective, diagnosis)
                    .getOrThrow()
                flow.first { it is LlmResult.Token }
            }
        }
    }

    // ── Memory: full 3-stage loop ─────────────────────────────────────────────
    // PSS Δ (proportional set size) before and after a complete S1→S2→S3 run.
    // Captures retained native + heap allocations; peak during inference may be
    // higher if the GC runs between measurement and completion.
    // Results are emitted via Log.i since BenchmarkRule does not natively surface
    // custom metrics — grep for "CognitiveLoopBenchmark" in logcat after the run.

    @Test
    fun memoryDelta_fullLoop_holmes() {
        assumeTrue(
            "Pass -e enableSlowBenchmarks true to enable this slow benchmark.",
            InstrumentationRegistry.getArguments().getString("enableSlowBenchmarks") != null,
        )
        val l = loop!!
        val deltas = mutableListOf<Long>()
        benchmarkRule.measureRepeated {
            val before = runWithTimingDisabled {
                Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            }
            runBlocking {
                val s1 = l.runStage1AffectiveMap(HOLMES_TEXT).getOrThrow()
                val s2 = l.runStage2DiagnosisOfThought(HOLMES_TEXT).getOrThrow()
                l.runStage3Intervention(HOLMES_TEXT, s1, s2).getOrThrow()
            }
            val after = runWithTimingDisabled {
                Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            }
            deltas += (after.totalPss - before.totalPss).toLong()
        }
        val sorted = deltas.sorted()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
        val mean = deltas.average().toLong()
        Log.i(TAG, "memoryDelta_fullLoop_holmes  totalPSS Δ — median=${median}KB mean=${mean}KB min=${sorted.first()}KB max=${sorted.last()}KB (n=${deltas.size})")
    }

    @Test
    fun memoryDelta_fullLoop_watson() {
        assumeTrue(
            "Pass -e enableSlowBenchmarks true to enable this slow benchmark.",
            InstrumentationRegistry.getArguments().getString("enableSlowBenchmarks") != null,
        )
        val l = loop!!
        val deltas = mutableListOf<Long>()
        benchmarkRule.measureRepeated {
            val before = runWithTimingDisabled {
                Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            }
            runBlocking {
                val s1 = l.runStage1AffectiveMap(WATSON_TEXT).getOrThrow()
                val s2 = l.runStage2DiagnosisOfThought(WATSON_TEXT).getOrThrow()
                l.runStage3Intervention(WATSON_TEXT, s1, s2).getOrThrow()
            }
            val after = runWithTimingDisabled {
                Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            }
            deltas += (after.totalPss - before.totalPss).toLong()
        }
        val sorted = deltas.sorted()
        val median = if (sorted.size % 2 == 0) (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2 else sorted[sorted.size / 2]
        val mean = deltas.average().toLong()
        Log.i(TAG, "memoryDelta_fullLoop_watson  totalPSS Δ — median=${median}KB mean=${mean}KB min=${sorted.first()}KB max=${sorted.last()}KB (n=${deltas.size})")
    }
}
