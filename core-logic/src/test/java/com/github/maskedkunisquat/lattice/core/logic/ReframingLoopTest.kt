package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReframingLoopTest {

    companion object {
        /** Minimal valid masked string — a single [PERSON_<uuid>] placeholder. */
        private const val MASKED = "[PERSON_00000000-0000-0000-0000-000000000001]"
    }

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeProvider(
        override val id: String,
        private val results: List<LlmResult>
    ) : LlmProvider {
        override suspend fun isAvailable() = true
        override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = results.asFlow()
    }

    private class FakeTransitEventDao : TransitEventDao {
        override suspend fun insertEvent(event: TransitEvent) = Unit
        override suspend fun getAllEvents(): List<TransitEvent> = emptyList()
        override fun getEventsFlow(): Flow<List<TransitEvent>> = flowOf(emptyList())
        override suspend fun deleteEventsForEntry(entryId: String) = Unit
    }

    private fun loopWithResponse(vararg tokens: String): ReframingLoop {
        val results: List<LlmResult> = tokens.map { LlmResult.Token(it) } + LlmResult.Complete
        val provider = FakeProvider("fake_local", results)
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        return ReframingLoop(orchestrator)
    }

    // ── Parser unit tests (no coroutines needed) ──────────────────────────────

    private val loop = ReframingLoop(
        orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = FakeProvider("local", listOf(LlmResult.Complete)),
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
    )

    @Test
    fun `parseAffectiveCoords - standard format`() {
        val result = loop.parseAffectiveCoords("v=0.6 a=-0.3")
        assertEquals(0.6f, result.valence, 0.001f)
        assertEquals(-0.3f, result.arousal, 0.001f)
        assertEquals(MoodLabel.SERENE, result.label)
        assertEquals(ReframingLoop.AffectiveSource.REGEX, result.source)
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
    }

    // ── End-to-end flow test ──────────────────────────────────────────────────

    @Test
    fun `runStage1AffectiveMap - parses token stream and returns label`() = runTest {
        // Model streams "v=", "-0.7", " a=", "0.8" as separate tokens
        val reframingLoop = loopWithResponse("v=", "-0.7", " a=", "0.8")
        val result = reframingLoop.runStage1AffectiveMap("$MASKED feels terrible and anxious.")
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
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        val result = ReframingLoop(orchestrator).runStage1AffectiveMap("")
        assertTrue(result.isFailure)
    }

    @Test
    fun `runStage1AffectiveMap - source is REGEX when MLP not provided`() = runTest {
        val reframingLoop = loopWithResponse("v=", "-0.5", " a=", "0.3")
        val result = reframingLoop.runStage1AffectiveMap("$MASKED makes me feel anxious.")
        assertTrue(result.isSuccess)
        assertEquals(ReframingLoop.AffectiveSource.REGEX, result.getOrThrow().source)
    }

    // 2.7-e inline review: PII enforcement belongs at the PiiShield boundary, not here.
    // runStage1AffectiveMap cannot distinguish "unmasked raw name" from "valid entry with no
    // person names at all" — both are non-blank strings without [PERSON_uuid] placeholders.
    // The stage trusts its callers and passes all text through; PiiShield.mask() must be
    // called upstream before any text reaches the ReframingLoop.
    @Test
    fun `runStage1AffectiveMap - passes placeholder-free text to LLM without rejection`() = runTest {
        val result = loopWithResponse("v=0.5 a=0.1")
            .runStage1AffectiveMap("John Smith made me feel terrible.")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `runStage1AffectiveMap - accepts blank text`() = runTest {
        val reframingLoop = loopWithResponse("v=", "0.0", " a=", "0.0")
        assertTrue(reframingLoop.runStage1AffectiveMap("").isSuccess)
    }

    // ── Stage 1: MLP path ─────────────────────────────────────────────────────

    private class FakeEmbeddingProvider(
        private val returnEmbedding: FloatArray
    ) : EmbeddingProvider() {
        override suspend fun generateEmbedding(text: String): FloatArray = returnEmbedding
    }

    private fun loopWithMlp(mlp: AffectiveMlp, embedding: FloatArray): ReframingLoop {
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = FakeProvider("local", listOf(LlmResult.Complete)),
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        return ReframingLoop(
            orchestrator = orchestrator,
            embeddingProvider = FakeEmbeddingProvider(embedding),
            affectiveMlp = mlp,
        )
    }

    @Test
    fun `runStage1AffectiveMap - uses MLP path when embeddingProvider and mlp are provided`() = runTest {
        val mlp = AffectiveMlp()
        val embedding = FloatArray(384) { 0.1f }
        val (expectedV, expectedA) = mlp.forward(embedding)

        val result = loopWithMlp(mlp, embedding).runStage1AffectiveMap("$MASKED I feel okay.")
        assertTrue(result.isSuccess)
        val mapped = result.getOrThrow()
        assertEquals(expectedV.coerceIn(-1f, 1f), mapped.valence, 0.001f)
        assertEquals(expectedA.coerceIn(-1f, 1f), mapped.arousal, 0.001f)
        assertEquals(ReframingLoop.AffectiveSource.MLP, mapped.source)
    }

    @Test
    fun `runStage1AffectiveMap - MLP source skips LLM entirely`() = runTest {
        // Orchestrator's local provider would emit an error if called;
        // MLP path must not call it.
        val errorResults = listOf(LlmResult.Error(RuntimeException("should not be called")))
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = FakeProvider("local", errorResults),
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        val mlp = AffectiveMlp()
        val reframingLoop = ReframingLoop(
            orchestrator = orchestrator,
            embeddingProvider = FakeEmbeddingProvider(FloatArray(384) { 0.0f }),
            affectiveMlp = mlp,
        )
        val result = reframingLoop.runStage1AffectiveMap("")
        assertTrue("MLP path must succeed without touching LLM", result.isSuccess)
        assertEquals(ReframingLoop.AffectiveSource.MLP, result.getOrThrow().source)
    }

    @Test
    fun `runStage1AffectiveMap - MLP output is clamped to minus1 to 1`() = runTest {
        // Force a weight configuration that pushes output outside [-1, 1] before tanh;
        // the Tanh activation in AffectiveMlp already bounds output, but coerceIn is
        // applied defensively in the caller — verify the final result is always in range.
        val mlp = AffectiveMlp()
        val embedding = FloatArray(384) { 1.0f }
        val result = loopWithMlp(mlp, embedding).runStage1AffectiveMap("")
        assertTrue(result.isSuccess)
        val mapped = result.getOrThrow()
        assertTrue("valence must be in [-1, 1]", mapped.valence in -1f..1f)
        assertTrue("arousal must be in [-1, 1]", mapped.arousal in -1f..1f)
    }

    // 2.7-l: embedder present but throws → source must be REGEX, not a crash
    private class ThrowingEmbeddingProvider : EmbeddingProvider() {
        override suspend fun generateEmbedding(text: String): FloatArray =
            throw RuntimeException("inference failed")
    }

    private fun loopWithThrowingEmbedder(): ReframingLoop {
        val results: List<LlmResult> =
            listOf("v=", "0.5", " a=", "-0.3").map { LlmResult.Token(it) } + LlmResult.Complete
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = FakeProvider("local", results),
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        return ReframingLoop(
            orchestrator = orchestrator,
            embeddingProvider = ThrowingEmbeddingProvider(),
            affectiveMlp = AffectiveMlp(),
        )
    }

    @Test
    fun `runStage1AffectiveMap - fallback to REGEX when embedder throws`() = runTest {
        val result = loopWithThrowingEmbedder()
            .runStage1AffectiveMap("$MASKED feels uncertain.")
        assertTrue("must succeed (not crash)", result.isSuccess)
        assertEquals(ReframingLoop.AffectiveSource.REGEX, result.getOrThrow().source)
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

    @Test
    fun `parseDotOutput - returns empty list when sentinel missing and no known labels present`() {
        // Current behaviour: greedy fallback scans for known labels; plain text with no
        // distortion keywords returns an empty list rather than throwing.
        val result = loop.parseDotOutput("The text seems okay. No distortions here.")
        assertTrue(result.distortions.isEmpty())
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
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        val result = ReframingLoop(orchestrator).runStage2DiagnosisOfThought("test")
        assertTrue(result.isFailure)
    }

    @Test
    fun `runStage2DiagnosisOfThought - MLP path used when distortionMlp is wired`() = runTest {
        // Wire a DistortionMlp whose b2 has all positive biases → all 12 classes fire.
        val mlp = DistortionMlp(
            w1 = FloatArray(DistortionMlp.OUT1 * DistortionMlp.IN),
            b1 = FloatArray(DistortionMlp.OUT1),
            w2 = FloatArray(DistortionMlp.OUT2 * DistortionMlp.OUT1),
            b2 = FloatArray(DistortionMlp.OUT2) { 100f },  // sigmoid(100) ≈ 1.0 → all true
        )
        val fakeEmbedder = object : EmbeddingProvider() {
            override suspend fun generateEmbedding(text: String) = FloatArray(384) { 0.1f }
        }
        val reframingLoop = loopWithResponse("DISTORTIONS: NONE")  // LLM would return NONE
        reframingLoop.distortionMlp   = mlp
        reframingLoop.affectiveMlp    = null  // only Stage 2 MLP is active here

        // Replace embeddingProvider via the backdoor field isn't possible (private val).
        // Instead wire it by constructing a loop directly with all three deps:
        val orchestrator = LlmOrchestrator(
            nanoProvider = object : LlmProvider {
                override val id = "nano"
                override suspend fun isAvailable() = false
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = FakeProvider("local", listOf(LlmResult.Complete)),
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
        )
        val loop = ReframingLoop(
            orchestrator      = orchestrator,
            embeddingProvider = fakeEmbedder,
            distortionMlp     = mlp,
        )
        val result = loop.runStage2DiagnosisOfThought("I always fail at everything.")
        assertTrue(result.isSuccess)
        val diagnosis = result.getOrThrow()
        assertEquals(ReframingLoop.DiagnosisSource.MLP, diagnosis.source)
        assertEquals(DistortionMlp.OUT2, diagnosis.distortions.size)  // all 12 classes fired
    }

    @Test
    fun `runStage2DiagnosisOfThought - falls back to LLM when distortionMlp is null`() = runTest {
        val response = "DISTORTIONS: Labeling"
        val reframingLoop = loopWithResponse(*response.map { it.toString() }.toTypedArray())
        // distortionMlp defaults to null → LLM path
        val result = reframingLoop.runStage2DiagnosisOfThought("I am such a loser.")
        assertTrue(result.isSuccess)
        val diagnosis = result.getOrThrow()
        assertEquals(ReframingLoop.DiagnosisSource.LLM, diagnosis.source)
        assertEquals(listOf(CognitiveDistortion.LABELING), diagnosis.distortions)
    }

    @Test
    fun `DiagnosisResult source defaults to LLM`() {
        val d = ReframingLoop.DiagnosisResult(emptyList(), "raw")
        assertEquals(ReframingLoop.DiagnosisSource.LLM, d.source)
    }

    // ── Stage 3: selectStrategy ───────────────────────────────────────────────

    @Test
    fun `selectStrategy - Q2 when negative valence and positive arousal`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            ReframingLoop.selectStrategy(-0.5f, 0.8f)
        )
    }

    @Test
    fun `selectStrategy - Q3 when negative valence and negative arousal`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            ReframingLoop.selectStrategy(-0.4f, -0.6f)
        )
    }

    @Test
    fun `selectStrategy - reflection for low-positive valence below threshold`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.REFLECTION,
            ReframingLoop.selectStrategy(0.0f, 0.7f)   // zero valence
        )
        assertEquals(
            ReframingLoop.ReframeStrategy.REFLECTION,
            ReframingLoop.selectStrategy(0.3f, 0.7f)   // below threshold
        )
        assertEquals(
            ReframingLoop.ReframeStrategy.REFLECTION,
            ReframingLoop.selectStrategy(0.39f, -0.5f) // just below threshold, Q4 arousal
        )
    }

    @Test
    fun `selectStrategy - strengths affirmation for high-positive valence at or above threshold`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.STRENGTHS_AFFIRMATION,
            ReframingLoop.selectStrategy(0.4f, 0.7f)   // exactly at threshold
        )
        assertEquals(
            ReframingLoop.ReframeStrategy.STRENGTHS_AFFIRMATION,
            ReframingLoop.selectStrategy(0.6f, -0.4f)  // well above threshold, Q4
        )
        assertEquals(
            ReframingLoop.ReframeStrategy.STRENGTHS_AFFIRMATION,
            ReframingLoop.selectStrategy(1.0f, 1.0f)   // max positive
        )
    }

    @Test
    fun `selectStrategy - Q2 on zero arousal boundary with negative valence`() {
        assertEquals(
            ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            ReframingLoop.selectStrategy(-0.1f, 0f)
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
        assertTrue(prompt.contains("fear", ignoreCase = true))
        assertTrue(prompt.contains("assumption", ignoreCase = true))
        assertTrue(prompt.contains("balanced", ignoreCase = true))
    }

    @Test
    fun `buildInterventionPrompt - Q3 prompt contains Behavioral Activation techniques`() {
        val prompt = loop.buildInterventionPrompt(
            maskedText = "Nothing ever works out for me.",
            strategy = ReframingLoop.ReframeStrategy.BEHAVIORAL_ACTIVATION,
            distortions = listOf(CognitiveDistortion.OVERGENERALIZATION),
        )
        assertTrue(prompt.contains("Overgeneralization"))
        assertTrue(prompt.contains("avoidance", ignoreCase = true))
        assertTrue(prompt.contains("temporary state", ignoreCase = true))
    }

    @Test
    fun `buildInterventionPrompt - reflection prompt asks what entry reveals about what matters`() {
        val prompt = loop.buildInterventionPrompt(
            maskedText = "meeting up with [PERSON_abc] later. might go to the park.",
            strategy = ReframingLoop.ReframeStrategy.REFLECTION,
            distortions = emptyList(),
        )
        assertTrue(prompt.contains("reveals", ignoreCase = true))
        assertTrue(prompt.contains("matters", ignoreCase = true))
        // Should not contain strengths-affirmation or BA-specific language
        assertFalse(prompt.contains("strength", ignoreCase = true))
        assertFalse(prompt.contains("avoidance", ignoreCase = true))
    }

    @Test
    fun `buildInterventionPrompt - empty distortions produces no-distortion context`() {
        val prompt = loop.buildInterventionPrompt(
            maskedText = "test",
            strategy = ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            distortions = emptyList(),
        )
        // When distortions is empty, no distortion line is injected into the prompt.
        assertFalse(prompt.contains("Distortions present:"))
    }

    // ── buildDisplayText ─────────────────────────────────────────────────────

    @Test
    fun `buildDisplayText - replaces PERSON token with at-nickname when nickname present`() {
        val personId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val person = Person(
            id = personId,
            firstName = "John",
            lastName = "Smith",
            nickname = "JJ",
            relationshipType = RelationshipType.FRIEND,
            vibeScore = 0f,
            isFavorite = false,
        )
        val result = loop.buildDisplayText(
            maskedText = "Hanging out with [PERSON_00000000-0000-0000-0000-000000000001] today.",
            personById = mapOf(personId to person),
            placeById  = emptyMap(),
        )
        assertEquals("Hanging out with @JJ today.", result)
    }

    @Test
    fun `buildDisplayText - falls back to firstName when nickname is null`() {
        val personId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val person = Person(
            id = personId,
            firstName = "Alice",
            lastName = null,
            nickname = null,
            relationshipType = RelationshipType.FRIEND,
            vibeScore = 0f,
            isFavorite = false,
        )
        val result = loop.buildDisplayText(
            maskedText = "Saw [PERSON_00000000-0000-0000-0000-000000000002] at the event.",
            personById = mapOf(personId to person),
            placeById  = emptyMap(),
        )
        assertEquals("Saw @Alice at the event.", result)
    }

    @Test
    fun `buildDisplayText - replaces PLACE token with bang-placeName`() {
        val placeId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val place = Place(
            id = placeId,
            name = "Central Park",
        )
        val result = loop.buildDisplayText(
            maskedText = "Went to [PLACE_00000000-0000-0000-0000-000000000010] yesterday.",
            personById = emptyMap(),
            placeById  = mapOf(placeId to place),
        )
        assertEquals("Went to !Central Park yesterday.", result)
    }

    @Test
    fun `buildDisplayText - unknown UUID tokens are left unchanged`() {
        val result = loop.buildDisplayText(
            maskedText = "Met [PERSON_00000000-0000-0000-0000-000000000099] at [PLACE_00000000-0000-0000-0000-000000000099].",
            personById = emptyMap(),
            placeById  = emptyMap(),
        )
        assertEquals("Met [PERSON_00000000-0000-0000-0000-000000000099] at [PLACE_00000000-0000-0000-0000-000000000099].", result)
    }

    @Test
    fun `buildDisplayText - returns maskedText unchanged when both maps are empty`() {
        val input = "Some text with [PERSON_00000000-0000-0000-0000-000000000001] inside."
        assertEquals(input, loop.buildDisplayText(input, emptyMap(), emptyMap()))
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
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
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
        override suspend fun updateActivity(activity: ActivityHierarchy) = Unit
        override suspend fun deleteActivity(activity: ActivityHierarchy) = Unit
        override fun getAllActivities(): kotlinx.coroutines.flow.Flow<List<ActivityHierarchy>> =
            kotlinx.coroutines.flow.flowOf(activities)

        override suspend fun getActivitiesByMaxDifficulty(max: Int): List<ActivityHierarchy> {
            lastMaxDifficulty = max
            return activities.filter { it.difficulty <= max }.sortedBy { it.difficulty }
        }
        override suspend fun deleteActivityById(id: java.util.UUID) = Unit
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
                override fun process(prompt: String, systemInstruction: String?): Flow<LlmResult> = flowOf()
            },
            localFallbackProvider = provider,
            transitEventDao = FakeTransitEventDao(),
            cloudEnabled = { false },
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
        // taskName is injected as the concrete next step; valueCategory is not part of the prompt.
        assertTrue(prompt.contains("Take a 5-minute walk"))
    }

    // ── Task 5.3-A: RAG Evidence injection ───────────────────────────────────

    @Test
    fun `buildInterventionPrompt - evidence block injected into Q2 prompt when entries provided`() {
        val evidence = JournalEntry(
            id = UUID.randomUUID(),
            timestamp = 0L,
            content = "[PERSON_abc] was kind and supportive yesterday.",
            valence = 0.8f,
            arousal = 0.2f,
            moodLabel = "SERENE",
            embedding = FloatArray(384),
        )
        val prompt = loop.buildInterventionPrompt(
            maskedText = "I feel [PERSON_abc] is angry at me.",
            strategy = ReframingLoop.ReframeStrategy.SOCRATIC_REALITY_TESTING,
            distortions = emptyList(),
            evidenceEntries = listOf(evidence),
        )
        assertTrue(
            "Evidence block must appear in Q2 prompt",
            prompt.contains("Past evidence that contradicts this belief")
        )
        assertTrue(
            "Evidence entry content must be present in prompt",
            prompt.contains("[PERSON_abc] was kind and supportive yesterday.")
        )
    }
}
