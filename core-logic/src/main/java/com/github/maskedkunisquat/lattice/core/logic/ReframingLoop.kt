package com.github.maskedkunisquat.lattice.core.logic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Orchestrates the three-stage sequential reframing pipeline.
 *
 * Each stage sends a focused prompt to [orchestrator] and parses the response.
 * All prompts receive PII-masked text only — callers must pre-mask via PiiShield.
 *
 * ## Stages
 * - Stage 1 ([runStage1AffectiveMap])       — Affective Mapping: valence/arousal → MoodLabel
 * - Stage 2 ([runStage2DiagnosisOfThought]) — DoT: facts-vs-beliefs → [CognitiveDistortion] list
 * - Stage 3 ([runStage3Intervention])        — Strategic Pivot: quadrant-aware CBT reframe
 */
class ReframingLoop(
    private val orchestrator: LlmOrchestrator,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    // ── Stage 1 ──────────────────────────────────────────────────────────────

    /**
     * Affective Mapping: prompts the model for valence/arousal coordinates and maps
     * them to a [MoodLabel] via [CircumplexMapper].
     *
     * @param maskedText PII-masked journal text (no raw names, places, or identifiers).
     * @return [Result.success] with [AffectiveMapResult], or [Result.failure] if the
     *   model is unavailable or its output cannot be parsed.
     */
    suspend fun runStage1AffectiveMap(maskedText: String): Result<AffectiveMapResult> =
        withContext(dispatcher) {
            runCatching {
                val raw = collectTokens(
                    orchestrator.process(buildAffectivePrompt(maskedText), "affective_map")
                )
                parseAffectiveCoords(raw)
            }
        }

    // ── Stage 2 ──────────────────────────────────────────────────────────────

    /**
     * Diagnosis of Thought: runs a chain-of-thought facts-vs-beliefs analysis then
     * identifies which of the 12 CBT [CognitiveDistortion]s are present.
     *
     * The model is asked to reason aloud (chain-of-thought) before emitting a final
     * `DISTORTIONS:` sentinel line. The full reasoning is preserved in
     * [DiagnosisResult.reasoning] for optional display or debugging.
     *
     * @param maskedText PII-masked journal text.
     * @return [Result.success] with [DiagnosisResult], or [Result.failure] on model
     *   error or unparseable output (missing sentinel).
     */
    suspend fun runStage2DiagnosisOfThought(maskedText: String): Result<DiagnosisResult> =
        withContext(dispatcher) {
            runCatching {
                val raw = collectTokens(
                    orchestrator.process(buildDotPrompt(maskedText), "dot_diagnosis")
                )
                parseDotOutput(raw)
            }
        }

    // ── Stage 3 ──────────────────────────────────────────────────────────────

    /**
     * Strategic Pivot: generates a quadrant-aware CBT reframe using the emotional
     * state from Stage 1 and the distortion diagnosis from Stage 2.
     *
     * Strategy selection:
     * - **Quadrant II** (v<0, a≥0 — angry/tense): Socratic questioning, Reality Testing,
     *   and probability calibration to challenge activated negative cognitions.
     * - **Quadrant III** (v<0, a<0 — depressed/fatigued): Behavioral Activation and
     *   Evidence for the Contrary to re-engage and counter helplessness.
     * - **Quadrant I/IV** (v≥0 — positive valence): Strengths affirmation to consolidate.
     *
     * The reframe is free-form natural language — no parsing sentinel required.
     *
     * @param maskedText   PII-masked journal text.
     * @param affectiveMap Output of Stage 1 (determines the quadrant / strategy).
     * @param diagnosis    Output of Stage 2 (distortions injected as context).
     * @return [Result.success] with [ReframeResult], or [Result.failure] on model error.
     */
    suspend fun runStage3Intervention(
        maskedText: String,
        affectiveMap: AffectiveMapResult,
        diagnosis: DiagnosisResult,
    ): Result<ReframeResult> = withContext(dispatcher) {
        runCatching {
            val strategy = selectStrategy(affectiveMap.valence, affectiveMap.arousal)
            val reframe = collectTokens(
                orchestrator.process(
                    buildInterventionPrompt(maskedText, strategy, diagnosis.distortions),
                    "intervention"
                )
            )
            ReframeResult(strategy = strategy, reframe = reframe)
        }
    }

    // ── Prompt builders ──────────────────────────────────────────────────────

    internal fun buildAffectivePrompt(maskedText: String): String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
        "You are an affective computing assistant. " +
        "Analyze text and output emotional coordinates only. " +
        "Never include explanations or additional text." +
        "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n" +
        "Analyze the emotional content of the following text.\n" +
        "Output ONLY one line in this exact format: v=<number> a=<number>\n" +
        "  v = valence  : -1.0 (very negative) to 1.0 (very positive)\n" +
        "  a = arousal  : -1.0 (calm / passive) to 1.0 (excited / active)\n" +
        "Numbers must be between -1.0 and 1.0. No other text.\n\n" +
        "Text: $maskedText" +
        "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

    internal fun buildDotPrompt(maskedText: String): String =
        "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
        "You are a CBT therapist applying the Diagnosis of Thought (DoT) method. " +
        "Reason step by step, then conclude with a DISTORTIONS line." +
        "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n" +
        "Analyze the following text using the three-step Diagnosis of Thought:\n\n" +
        "Step 1 — Facts vs. Beliefs:\n" +
        "Separate what is objectively stated (facts) from what is interpreted, " +
        "assumed, or felt (beliefs/thoughts).\n\n" +
        "Step 2 — Contrastive Analysis:\n" +
        "For each belief, identify the cognitive distortion from this list:\n" +
        "${CognitiveDistortion.promptList}\n\n" +
        "Step 3 — Final diagnosis:\n" +
        "On the last line output ONLY:\n" +
        "DISTORTIONS: <comma-separated distortion names, or NONE if none found>\n\n" +
        "Text: $maskedText" +
        "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

    /**
     * Selects the intervention strategy based on circumplex quadrant.
     * v<0 and a≥0 → Quadrant II → [ReframeStrategy.SOCRATIC_REALITY_TESTING]
     * v<0 and a<0  → Quadrant III → [ReframeStrategy.BEHAVIORAL_ACTIVATION]
     * v≥0 (any a)  → Positive valence → [ReframeStrategy.STRENGTHS_AFFIRMATION]
     */
    internal fun selectStrategy(valence: Float, arousal: Float): ReframeStrategy = when {
        valence < 0f && arousal >= 0f -> ReframeStrategy.SOCRATIC_REALITY_TESTING
        valence < 0f && arousal < 0f  -> ReframeStrategy.BEHAVIORAL_ACTIVATION
        else                          -> ReframeStrategy.STRENGTHS_AFFIRMATION
    }

    internal fun buildInterventionPrompt(
        maskedText: String,
        strategy: ReframeStrategy,
        distortions: List<CognitiveDistortion>,
    ): String {
        val distortionContext = if (distortions.isEmpty())
            "No specific cognitive distortions were identified."
        else
            "Identified cognitive distortions: ${distortions.joinToString(", ") { it.label }}"

        val (systemMsg, techniqueBlock) = when (strategy) {
            ReframeStrategy.SOCRATIC_REALITY_TESTING -> Pair(
                "You are a compassionate CBT therapist. The client is experiencing " +
                "high-arousal negative emotions — anxiety, anger, or tension. " +
                "Guide them using Socratic questioning and Reality Testing.",
                "Generate a concise (2–4 sentence) CBT reframe using:\n" +
                "1. Socratic questioning — invite the client to examine the evidence " +
                    "for and against their interpretation.\n" +
                "2. Reality testing — gently check whether the situation matches the belief.\n" +
                "3. Probability calibration — help them assess the realistic likelihood " +
                    "of the feared outcome."
            )
            ReframeStrategy.BEHAVIORAL_ACTIVATION -> Pair(
                "You are a compassionate CBT therapist. The client is experiencing " +
                "low-arousal negative emotions — depression, fatigue, or hopelessness. " +
                "Guide them using Behavioral Activation and Evidence for the Contrary.",
                "Generate a concise (2–4 sentence) CBT reframe using:\n" +
                "1. Evidence for the contrary — identify specific evidence or past " +
                    "experiences that challenge the negative belief.\n" +
                "2. Behavioral activation — suggest one small, concrete action to break " +
                    "the cycle and restore a sense of agency."
            )
            ReframeStrategy.STRENGTHS_AFFIRMATION -> Pair(
                "You are a compassionate CBT therapist. The client is experiencing " +
                "positive emotions. Help them consolidate and build on this state.",
                "Generate a brief (2–3 sentence) affirming reflection that helps the " +
                "client recognise their strengths and anchor this positive experience."
            )
        }

        return "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
            systemMsg +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n" +
            "The client wrote:\n\"$maskedText\"\n\n" +
            "$distortionContext\n\n" +
            "$techniqueBlock\n\n" +
            "Address the client directly and warmly. Do not name the techniques." +
            "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    internal fun parseAffectiveCoords(raw: String): AffectiveMapResult {
        val v = V_REGEX.find(raw)?.groupValues?.get(1)?.toFloatOrNull()
            ?: throw IllegalStateException("No valence coordinate in model output: \"$raw\"")
        val a = A_REGEX.find(raw)?.groupValues?.get(1)?.toFloatOrNull()
            ?: throw IllegalStateException("No arousal coordinate in model output: \"$raw\"")
        val vc = v.coerceIn(-1f, 1f)
        val ac = a.coerceIn(-1f, 1f)
        return AffectiveMapResult(
            valence = vc,
            arousal = ac,
            label = CircumplexMapper.getLabel(vc, ac)
        )
    }

    /**
     * Finds the last `DISTORTIONS:` sentinel line in [raw] (the model may reason
     * aloud and repeat itself before settling), splits the CSV, and resolves each
     * token to a [CognitiveDistortion]. Unrecognised tokens are silently dropped.
     */
    internal fun parseDotOutput(raw: String): DiagnosisResult {
        val sentinelLine = raw.lines()
            .lastOrNull { it.contains("DISTORTIONS:", ignoreCase = true) }
            ?: throw IllegalStateException(
                "No DISTORTIONS: sentinel in model output: \"$raw\""
            )

        val colonIdx = sentinelLine.indexOf("DISTORTIONS:", ignoreCase = true)
        val csv = sentinelLine.substring(colonIdx + "DISTORTIONS:".length).trim()

        if (csv.equals("NONE", ignoreCase = true) || csv.isEmpty()) {
            return DiagnosisResult(distortions = emptyList(), reasoning = raw)
        }

        val distortions = csv.split(",").mapNotNull { CognitiveDistortion.fromLabel(it) }
        return DiagnosisResult(distortions = distortions, reasoning = raw)
    }

    // ── Token stream collector ───────────────────────────────────────────────

    private suspend fun collectTokens(flow: Flow<LlmResult>): String {
        val sb = StringBuilder()
        flow.collect { result ->
            when (result) {
                is LlmResult.Token    -> sb.append(result.text)
                is LlmResult.Error    -> throw result.cause
                is LlmResult.Complete -> Unit
            }
        }
        return sb.toString().trim()
    }

    // ── Result types ─────────────────────────────────────────────────────────

    /**
     * Output of Stage 1. [valence] and [arousal] are clamped to [-1, 1].
     */
    data class AffectiveMapResult(
        val valence: Float,
        val arousal: Float,
        val label: MoodLabel
    )

    /**
     * Output of Stage 2.
     *
     * @param distortions Identified [CognitiveDistortion]s. Empty when none detected.
     * @param reasoning   Full raw model output including the chain-of-thought
     *   facts/beliefs analysis. Preserved for debugging and optional UI display.
     */
    data class DiagnosisResult(
        val distortions: List<CognitiveDistortion>,
        val reasoning: String
    )

    /**
     * The CBT intervention strategy selected by Stage 3, derived from the circumplex quadrant.
     */
    enum class ReframeStrategy {
        /** Quadrant II (v<0, a≥0): Socratic questioning + Reality Testing + probability. */
        SOCRATIC_REALITY_TESTING,
        /** Quadrant III (v<0, a<0): Behavioral Activation + Evidence for the Contrary. */
        BEHAVIORAL_ACTIVATION,
        /** Quadrant I/IV (v≥0): Positive-valence strengths affirmation. */
        STRENGTHS_AFFIRMATION,
    }

    /**
     * Output of Stage 3.
     *
     * @param strategy The intervention approach that was applied.
     * @param reframe  The generated CBT reframe, addressed directly to the client.
     */
    data class ReframeResult(
        val strategy: ReframeStrategy,
        val reframe: String,
    )

    companion object {
        // Both regexes tolerate optional spaces around `=` and an optional leading `-`.
        private val V_REGEX = Regex("""v\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val A_REGEX = Regex("""a\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    }
}
