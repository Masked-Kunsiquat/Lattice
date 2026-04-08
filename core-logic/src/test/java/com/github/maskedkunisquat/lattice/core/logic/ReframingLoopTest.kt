package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
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
            nanoProvider = FakeProvider("nano", emptyList<LlmResult>().also { }).let {
                object : LlmProvider {
                    override val id = "nano"
                    override suspend fun isAvailable() = false
                    override fun process(prompt: String): Flow<LlmResult> = flowOf()
                }
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
}
