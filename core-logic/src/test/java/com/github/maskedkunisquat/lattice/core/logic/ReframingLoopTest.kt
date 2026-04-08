package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReframingLoopTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeProvider(
        override val id: String,
        private val results: List<LlmResult>
    ) : LlmProvider {
        override suspend fun isAvailable() = true
        override fun process(prompt: String): Flow<LlmResult> = results.asFlow()
    }

    private class FakeTransitEventDao : TransitEventDao {
        override suspend fun insertEvent(event: TransitEvent) = Unit
        override suspend fun getAllEvents(): List<TransitEvent> = emptyList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(emptyList())
    }

    private fun loopWithResponse(vararg tokens: String): ReframingLoop {
        val results: List<LlmResult> = tokens.map { LlmResult.Token(it) } + LlmResult.Complete
        val provider = FakeProvider("fake_local", results)
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        return ReframingLoop(orchestrator)
    }

    // ── Parser unit tests (no coroutines needed) ──────────────────────────────

    private val loop = ReframingLoop(
        orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = FakeProvider("local", listOf(LlmResult.Complete)),
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
    )

    @Test
    fun `parseAffectiveCoords - standard format`() {
        val result = loop.parseAffectiveCoords("v=0.6 a=-0.3")
        assertEquals(0.6f, result.valence, 0.001f)
        assertEquals(-0.3f, result.arousal, 0.001f)
        assertEquals(MoodLabel.SERENE, result.label)
    }

    @Test
    fun `parseAffectiveCoords - spaces around equals`() {
        val result = loop.parseAffectiveCoords("v = 0.8 a = 0.9")
        assertEquals(0.8f, result.valence, 0.001f)
        assertEquals(0.9f, result.arousal, 0.001f)
        assertEquals(MoodLabel.EXCITED, result.label)
    }

    @Test
    fun `parseAffectiveCoords - integer values`() {
        val result = loop.parseAffectiveCoords("v=1 a=-1")
        assertEquals(1.0f, result.valence, 0.001f)
        assertEquals(-1.0f, result.arousal, 0.001f)
    }

    @Test
    fun `parseAffectiveCoords - clamps out-of-range values`() {
        val result = loop.parseAffectiveCoords("v=1.5 a=-2.0")
        assertEquals(1.0f, result.valence, 0.001f)
        assertEquals(-1.0f, result.arousal, 0.001f)
    }

    @Test
    fun `parseAffectiveCoords - output embedded in extra text`() {
        // Model may prepend a stray word before the coordinates
        val result = loop.parseAffectiveCoords("Sure! v=0.2 a=0.5")
        assertEquals(0.2f, result.valence, 0.001f)
        assertEquals(0.5f, result.arousal, 0.001f)
    }

    @Test(expected = IllegalStateException::class)
    fun `parseAffectiveCoords - throws when valence missing`() {
        loop.parseAffectiveCoords("a=0.5")
    }

    @Test(expected = IllegalStateException::class)
    fun `parseAffectiveCoords - throws when arousal missing`() {
        loop.parseAffectiveCoords("v=0.3")
    }

    @Test(expected = IllegalStateException::class)
    fun `parseAffectiveCoords - throws on empty output`() {
        loop.parseAffectiveCoords("")
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    @Test
    fun `buildAffectivePrompt - contains masked text`() {
        val prompt = loop.buildAffectivePrompt("I feel [PERSON_abc] ignored me today.")
        assertTrue(prompt.contains("[PERSON_abc]"))
        assertTrue(prompt.contains("v=<number> a=<number>"))
        assertTrue(prompt.contains("<|begin_of_text|>"))
        assertTrue(prompt.contains("<|eot_id|>"))
    }

    // ── End-to-end flow test ──────────────────────────────────────────────────

    @Test
    fun `runStage1AffectiveMap - parses token stream and returns label`() = runTest {
        // Model streams "v=", "-0.7", " a=", "0.8" as separate tokens
        val reframingLoop = loopWithResponse("v=", "-0.7", " a=", "0.8")
        val result = reframingLoop.runStage1AffectiveMap("I feel terrible and anxious.")
        assertTrue(result.isSuccess)
        val mapped = result.getOrThrow()
        assertEquals(-0.7f, mapped.valence, 0.001f)
        assertEquals(0.8f, mapped.arousal, 0.001f)
        assertEquals(MoodLabel.TENSE, mapped.label) // a=0.8 > abs(v)=0.7 → TENSE
    }

    @Test
    fun `runStage1AffectiveMap - returns failure when model emits error`() = runTest {
        val results = listOf(LlmResult.Error(RuntimeException("model not loaded")))
        val provider = FakeProvider("fake_local", results)
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        val result = ReframingLoop(orchestrator).runStage1AffectiveMap("test")
        assertTrue(result.isFailure)
    }

    // ── Stage 2: parseDotOutput ───────────────────────────────────────────────

    @Test
    fun `parseDotOutput - single distortion`() {
        val result = loop.parseDotOutput("DISTORTIONS: Catastrophizing")
        assertEquals(listOf(CognitiveDistortion.CATASTROPHIZING), result.distortions)
    }

    @Test
    fun `parseDotOutput - multiple distortions`() {
        val result = loop.parseDotOutput(
            "Step 1: facts...\nStep 2: beliefs...\nDISTORTIONS: Labeling, Fortune Telling, All-or-Nothing"
        )
        assertEquals(
            listOf(
                CognitiveDistortion.LABELING,
                CognitiveDistortion.FORTUNE_TELLING,
                CognitiveDistortion.ALL_OR_NOTHING,
            ),
            result.distortions
        )
    }

    @Test
    fun `parseDotOutput - NONE produces empty list`() {
        val result = loop.parseDotOutput("DISTORTIONS: NONE")
        assertTrue(result.distortions.isEmpty())
    }

    @Test
    fun `parseDotOutput - case-insensitive sentinel`() {
        val result = loop.parseDotOutput("distortions: Personalization")
        assertEquals(listOf(CognitiveDistortion.PERSONALIZATION), result.distortions)
    }

    @Test
    fun `parseDotOutput - normalises hyphen and case variations`() {
        // Model may output "All or Nothing" without the hyphen
        val result = loop.parseDotOutput("DISTORTIONS: All or Nothing, emotional reasoning")
        assertEquals(
            listOf(CognitiveDistortion.ALL_OR_NOTHING, CognitiveDistortion.EMOTIONAL_REASONING),
            result.distortions
        )
    }

    @Test
    fun `parseDotOutput - unrecognised tokens silently dropped`() {
        val result = loop.parseDotOutput("DISTORTIONS: Catastrophizing, MadeUpDistortion, Blame")
        assertEquals(
            listOf(CognitiveDistortion.CATASTROPHIZING, CognitiveDistortion.BLAME),
            result.distortions
        )
    }

    @Test
    fun `parseDotOutput - uses last DISTORTIONS line when model repeats itself`() {
        val raw = "DISTORTIONS: Labeling\n...\nDISTORTIONS: Catastrophizing, Mind Reading"
        val result = loop.parseDotOutput(raw)
        assertEquals(
            listOf(CognitiveDistortion.CATASTROPHIZING, CognitiveDistortion.MIND_READING),
            result.distortions
        )
    }

    @Test
    fun `parseDotOutput - preserves full reasoning in result`() {
        val raw = "Facts: X. Beliefs: Y.\nDISTORTIONS: Should Statements"
        val result = loop.parseDotOutput(raw)
        assertEquals(raw, result.reasoning)
    }

    @Test(expected = IllegalStateException::class)
    fun `parseDotOutput - throws when sentinel missing`() {
        loop.parseDotOutput("The text seems okay. No distortions here.")
    }

    // ── Stage 2: buildDotPrompt ───────────────────────────────────────────────

    @Test
    fun `buildDotPrompt - contains masked text and all 12 distortions`() {
        val prompt = loop.buildDotPrompt("I feel [PERSON_abc] hates me.")
        assertTrue(prompt.contains("[PERSON_abc]"))
        assertTrue(prompt.contains("DISTORTIONS:"))
        CognitiveDistortion.entries.forEach { distortion ->
            assertTrue(
                "Prompt must list ${distortion.label}",
                prompt.contains(distortion.label, ignoreCase = true)
            )
        }
        assertTrue(prompt.contains("<|begin_of_text|>"))
        assertTrue(prompt.contains("<|eot_id|>"))
    }

    // ── Stage 2: end-to-end flow ──────────────────────────────────────────────

    @Test
    fun `runStage2DiagnosisOfThought - parses token stream and returns distortions`() = runTest {
        val response = "Facts: X. Beliefs: Y.\nDISTORTIONS: Catastrophizing, Labeling"
        val reframingLoop = loopWithResponse(*response.map { it.toString() }.toTypedArray())
        val result = reframingLoop.runStage2DiagnosisOfThought("I know everything will go wrong.")
        assertTrue(result.isSuccess)
        val diagnosis = result.getOrThrow()
        assertEquals(
            listOf(CognitiveDistortion.CATASTROPHIZING, CognitiveDistortion.LABELING),
            diagnosis.distortions
        )
    }

    @Test
    fun `runStage2DiagnosisOfThought - returns failure when model emits error`() = runTest {
        val results = listOf(LlmResult.Error(RuntimeException("session closed")))
        val provider = FakeProvider("fake_local", results)
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        val result = ReframingLoop(orchestrator).runStage2DiagnosisOfThought("test")
        assertTrue(result.isFailure)
    }

    // ── Stage 3: selectStrategy ───────────────────────────────────────────────

    @Test
    fun `selectStrategy - Q2 when negative valence and positive arousal`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            loop.selectStrategy(-0.5f, 0.8f)
        )
    }

    @Test
    fun `selectStrategy - Q3 when negative valence and negative arousal`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            loop.selectStrategy(-0.4f, -0.6f)
        )
    }

    @Test
    fun `selectStrategy - strengths affirmation for positive valence`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.STRENGTHS_AFFIRMATION,
            loop.selectStrategy(0.3f, 0.7f)  // Q1
        )
        assertEquals(
            ReframingLoop.ReframeStrategy.STRENGTHS_AFFIRMATION,
            loop.selectStrategy(0.6f, -0.4f)  // Q4
        )
    }

    @Test
    fun `selectStrategy - Q2 on zero arousal boundary with negative valence`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            loop.selectStrategy(-0.1f, 0f)
        )
    }

    // ── Stage 3: buildInterventionPrompt ─────────────────────────────────────

    @Test
    fun `buildInterventionPrompt - Q2 prompt contains Socratic techniques`() {
        val prompt = loop.buildInterventionPrompt(
            maskedText = "I know [PERSON_abc] is angry at me.",
            strategy = ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            distortions = listOf(CognitiveDistortion.MIND_READING),
        )
        assertTrue(prompt.contains("[PERSON_abc]"))
        assertTrue(prompt.contains("Mind Reading"))
        assertTrue(prompt.contains("Socratic", ignoreCase = true))
        assertTrue(prompt.contains("Reality", ignoreCase = true))
        assertTrue(prompt.contains("probability", ignoreCase = true))
        assertTrue(prompt.contains("<|begin_of_text|>"))
    }

    @Test
    fun `buildInterventionPrompt - Q3 prompt contains Behavioral Activation techniques`() {
        val prompt = loop.buildInterventionPrompt(
            maskedText = "Nothing ever works out for me.",
            strategy = ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            distortions = listOf(CognitiveDistortion.OVERGENERALIZATION),
        )
        assertTrue(prompt.contains("Overgeneralization"))
        assertTrue(prompt.contains("Behavioral", ignoreCase = true))
        assertTrue(prompt.contains("contrary", ignoreCase = true))
        assertTrue(prompt.contains("<|begin_of_text|>"))
    }

    @Test
    fun `buildInterventionPrompt - empty distortions produces no-distortion context`() {
        val prompt = loop.buildInterventionPrompt(
            maskedText = "test",
            strategy = ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            distortions = emptyList(),
        )
        assertTrue(prompt.contains("No specific cognitive distortions were identified."))
    }

    // ── Stage 3: end-to-end flow ──────────────────────────────────────────────

    @Test
    fun `runStage3Intervention - Q2 route returns SOCRATIC_REALITY_TESTING strategy`() = runTest {
        val reframe = "Have you considered whether there is evidence against this view?"
        val reframingLoop = loopWithResponse(*reframe.map { it.toString() }.toTypedArray())

        val affectiveMap = ReframingLoop.AffectiveMapResult(-0.6f, 0.7f, MoodLabel.TENSE)
        val diagnosis = ReframingLoop.DiagnosisResult(
            listOf(CognitiveDistortion.FORTUNE_TELLING), "reasoning"
        )

        val result = reframingLoop.runStage3Intervention("test", affectiveMap, diagnosis)
        assertTrue(result.isSuccess)
        val reframeResult = result.getOrThrow()
        assertEquals(ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING, reframeResult.strategy)
        assertEquals(reframe, reframeResult.reframe)
    }

    @Test
    fun `runStage3Intervention - Q3 route returns BEHAVIORAL_ACTIVATION strategy`() = runTest {
        val reframe = "One small step today can begin to shift this feeling."
        val reframingLoop = loopWithResponse(*reframe.map { it.toString() }.toTypedArray())

        val affectiveMap = ReframingLoop.AffectiveMapResult(-0.7f, -0.5f, MoodLabel.DEPRESSED)
        val diagnosis = ReframingLoop.DiagnosisResult(emptyList(), "reasoning")

        val result = reframingLoop.runStage3Intervention("test", affectiveMap, diagnosis)
        assertTrue(result.isSuccess)
        assertEquals(
            ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            result.getOrThrow().strategy
        )
    }

    @Test
    fun `runStage3Intervention - returns failure when model emits error`() = runTest {
        val results = listOf(LlmResult.Error(RuntimeException("model unavailable")))
        val provider = FakeProvider("fake_local", results)
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        val affectiveMap = ReframingLoop.AffectiveMapResult(-0.5f, 0.5f, MoodLabel.ANGRY)
        val diagnosis = ReframingLoop.DiagnosisResult(emptyList(), "")
        val result = ReframingLoop(orchestrator)
            .runStage3Intervention("test", affectiveMap, diagnosis)
        assertTrue(result.isFailure)
    }

    // ── Task 5.2: Behavioral Activation integration ───────────────────────────

    private class FakeActivityHierarchyDao(
        private val activities: List<ActivityHierarchy> = emptyList()
    ) : ActivityHierarchyDao {
        var lastMaxDifficulty: Int? = null

        override suspend fun insertActivity(activity: ActivityHierarchy) = Unit

        override suspend fun getActivitiesByMaxDifficulty(max: Int): List<ActivityHierarchy> {
            lastMaxDifficulty = max
            return activities.filter { it.difficulty <= max }.sortedBy { it.difficulty }
        }
    }

    private fun loopWithResponseAndDao(
        dao: ActivityHierarchyDao,
        vararg tokens: String
    ): ReframingLoop {
        val results: List<LlmResult> = tokens.map { LlmResult.Token(it) } + LlmResult.Complete
        val provider = FakeProvider("fake_local", results)
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = false,
        )
        return ReframingLoop(orchestrator, dao)
    }

    @Test
    fun `BA - difficulty gate enforced - only activities at or below max are returned`() = runTest {
        val hard = ActivityHierarchy(UUID.randomUUID(), "Hard task", difficulty = 8, valueCategory = "health")
        val easy = ActivityHierarchy(UUID.randomUUID(), "Easy walk", difficulty = 3, valueCategory = "health")
        val dao = FakeActivityHierarchyDao(listOf(hard, easy))

        val reframingLoop = loopWithResponseAndDao(dao, "reframe text")
        val affectiveMap = ReframingLoop.AffectiveMapResult(-0.7f, -0.5f, MoodLabel.DEPRESSED)
        val diagnosis = ReframingLoop.DiagnosisResult(emptyList(), "")

        reframingLoop.runStage3Intervention("health matters", affectiveMap, diagnosis)

        // DAO must have been queried with BA_MAX_DIFFICULTY; hard task (8) must be excluded
        assertEquals(ReframingLoop.BA_MAX_DIFFICULTY, dao.lastMaxDifficulty)
        val returned = dao.getActivitiesByMaxDifficulty(ReframingLoop.BA_MAX_DIFFICULTY)
        assertTrue(returned.none { it.difficulty > ReframingLoop.BA_MAX_DIFFICULTY })
        assertTrue(returned.any { it.taskName == "Easy walk" })
        assertTrue(returned.none { it.taskName == "Hard task" })
    }

    @Test
    fun `BA - empty hierarchy handled gracefully - Stage 3 proceeds without BA block`() = runTest {
        val dao = FakeActivityHierarchyDao(emptyList())
        val reframingLoop = loopWithResponseAndDao(dao, "You can do this.")

        val affectiveMap = ReframingLoop.AffectiveMapResult(-0.6f, -0.4f, MoodLabel.DEPRESSED)
        val diagnosis = ReframingLoop.DiagnosisResult(emptyList(), "")

        val result = reframingLoop.runStage3Intervention("test", affectiveMap, diagnosis)
        assertTrue(result.isSuccess)
        assertEquals(ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION, result.getOrThrow().strategy)
    }

    @Test
    fun `BA - activity injected into prompt string when available`() {
        val activity = ActivityHierarchy(
            UUID.randomUUID(),
            taskName = "Take a 5-minute walk",
            difficulty = 2,
            valueCategory = "health"
        )
        val prompt = loop.buildInterventionPrompt(
            maskedText = "I feel exhausted and hopeless.",
            strategy = ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            distortions = emptyList(),
            baActivity = activity,
        )
        assertTrue(prompt.contains("Take a 5-minute walk"))
        assertTrue(prompt.contains("health"))
    }
}
