package com.github.maskedkunisquat.lattice.core.logic

import android.util.Log
import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
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
 *
 * @param activityHierarchyDao Optional DAO for the BA activity hierarchy. When provided
 *   and Stage 3 selects [ReframeStrategy.BEHAVIORAL_ACTIVATION], the lowest-difficulty
 *   activity (up to difficulty [BA_MAX_DIFFICULTY]) whose [ActivityHierarchy.valueCategory]
 *   aligns with the entry context is injected into the prompt as a concrete first step.
 * @param searchRepository Optional repository for RAG evidence retrieval. When provided,
 *   Stage 3 fetches positive past entries anchored to the same entities and injects them
 *   as an "Evidence for the Contrary" block in both Q2 and Q3 prompts.
 */
class ReframingLoop(
    private val orchestrator: LlmOrchestrator,
    private val activityHierarchyDao: ActivityHierarchyDao? = null,
    private val searchRepository: SearchRepository? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val embeddingProvider: EmbeddingProvider? = null,
    private val affectiveMlp: AffectiveMlp? = null,
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
    suspend fun runStage1AffectiveMap(maskedText: String): Result<AffectiveMapResult> {
        // PII enforcement belongs at the PiiShield boundary (PiiShield.mask / isFullyMasked),
        // not here. The previous regex check incorrectly rejected valid placeholder-free text
        // (entries with no names) and accepted mixed raw/placeholder input.
        return withContext(dispatcher) {
            runCatching {
                val mlp = affectiveMlp
                val embedder = embeddingProvider
                val mlpResult: AffectiveMapResult? = if (mlp != null && embedder != null) {
                    runCatching {
                        val embedding = embedder.generateEmbedding(maskedText)
                        val (v, a) = mlp.forward(embedding)
                        val vc = v.coerceIn(-1f, 1f)
                        val ac = a.coerceIn(-1f, 1f)
                        Log.d(TAG, "Stage1: source=mlp")
                        AffectiveMapResult(
                            valence = vc,
                            arousal = ac,
                            label = CircumplexMapper.getLabel(vc, ac),
                            source = AffectiveSource.MLP,
                        )
                    }.onFailure { e ->
                        Log.w(TAG, "Stage1: MLP path threw, falling back to regex", e)
                    }.getOrNull()
                } else null

                mlpResult ?: run {
                    val raw = collectTokens(
                        orchestrator.process(buildAffectivePrompt(maskedText), "affective_map")
                    )
                    Log.d(TAG, "Stage1: source=regex")
                    parseAffectiveCoords(raw)
                }
            }
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

            // For Quadrant III (Behavioral Activation), look up the most accessible activity
            // whose valueCategory aligns with the entry context.
            val baActivity = if (strategy == ReframeStrategy.BEHAVIORAL_ACTIVATION) {
                pickBaActivity(maskedText)
            } else null

            // For Q2 and Q3 (negative valence), fetch positive past entries anchored to the
            // same entities as concrete "Evidence for the Contrary".
            val evidenceEntries = if (affectiveMap.valence < 0f) {
                fetchEvidenceEntries(maskedText)
            } else emptyList()

            val reframe = collectTokens(
                orchestrator.process(
                    buildInterventionPrompt(
                        maskedText, strategy, diagnosis.distortions, baActivity, evidenceEntries
                    ),
                    "intervention"
                )
            )
            if (reframe.isBlank()) throw IllegalStateException("Model returned an empty reframe.")
            ReframeResult(strategy = strategy, reframe = reframe)
        }
    }

    /**
     * Fetches activities up to [BA_MAX_DIFFICULTY] and returns the first whose
     * [ActivityHierarchy.valueCategory] appears as a word in [maskedText]. Falls back
     * to the easiest activity overall if none match. Returns null when the DAO is absent
     * or the hierarchy is empty — Stage 3 then proceeds without a BA suggestion block.
     */
    private suspend fun pickBaActivity(maskedText: String): ActivityHierarchy? {
        val dao = activityHierarchyDao ?: return null
        val candidates = dao.getActivitiesByMaxDifficulty(BA_MAX_DIFFICULTY)
        if (candidates.isEmpty()) return null
        val tokens = maskedText.lowercase(java.util.Locale.ROOT).split(Regex("\\W+")).filter { it.isNotBlank() }.toSet()
        return candidates.firstOrNull { it.valueCategory.lowercase(java.util.Locale.ROOT) in tokens }
            ?: candidates.first()
    }

    /**
     * Extracts `[PERSON_UUID]` placeholders from [maskedText] and queries
     * [searchRepository] for positive past entries anchored to those same entities.
     * Returns an empty list when [searchRepository] is null or no evidence is found.
     */
    private suspend fun fetchEvidenceEntries(maskedText: String): List<JournalEntry> {
        val repo = searchRepository ?: return emptyList()
        val placeholders = PLACEHOLDER_REGEX.findAll(maskedText)
            .map { it.value }
            .toSet()
        return repo.findEvidenceEntries(placeholders)
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


    internal fun buildInterventionPrompt(
        maskedText: String,
        strategy: ReframeStrategy,
        distortions: List<CognitiveDistortion>,
        baActivity: ActivityHierarchy? = null,
        evidenceEntries: List<JournalEntry> = emptyList(),
    ): String {
        val distortionContext = if (distortions.isEmpty())
            "No specific cognitive distortions were identified."
        else
            "Identified cognitive distortions: ${distortions.joinToString(", ") { it.label }}"

        val techniqueBlock = when (strategy) {
            ReframeStrategy.SOCRATIC_REALITY_TESTING ->
                "Use Socratic Reality Testing (probability calibration): write a 2–3 sentence " +
                "reframe in the writer's own voice that:\n" +
                "1. Names the core fear or assumption driving the thought.\n" +
                "2. Questions its certainty — what is the actual probability this belief is true?\n" +
                "3. Lands on a more balanced, realistic interpretation."

            ReframeStrategy.BEHAVIORAL_ACTIVATION -> {
                val stepTwo = if (evidenceEntries.isNotEmpty())
                    "2. Counters the belief with a specific past experience contrary to it " +
                    "(draw from the Evidence for the Contrary provided above).\n"
                else
                    "2. Offers a realistic counterthought contrary to the belief — " +
                    "acknowledge difficulty without inventing past journal evidence.\n"
                val stepThree = if (baActivity != null)
                    "3. Ends with one small concrete step: \"${baActivity.taskName}\" (value area: ${baActivity.valueCategory})."
                else
                    "3. Ends with one small concrete action to restore a sense of agency."
                "Use Behavioral Activation: write a 2–3 sentence reframe in the writer's own voice that:\n" +
                "1. Acknowledges the difficulty without catastrophising.\n" +
                stepTwo +
                stepThree
            }

            ReframeStrategy.STRENGTHS_AFFIRMATION ->
                "Write a 2–3 sentence reframe in the writer's own voice that:\n" +
                "1. Names the strength or effort the entry reveals.\n" +
                "2. Connects it to a broader pattern or value.\n" +
                "3. Anchors what made this moment meaningful."
        }

        val evidenceBullets = evidenceEntries.mapNotNull { entry ->
            val snippet = entry.content?.takeIf { it.isNotBlank() }?.let {
                if (it.length > 200) it.take(200) + "…" else it
            } ?: return@mapNotNull null
            "- $snippet"
        }
        val evidenceBlock = if (evidenceBullets.isNotEmpty()) {
            "\n\nEvidence for the Contrary (past journal moments that contradict this belief):\n" +
                evidenceBullets.joinToString("\n")
        } else ""

        return "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" +
            "You are a CBT journaling assistant. Rewrite the journal entry as a concise " +
            "first-person thought record — the writer challenging their own thought. " +
            "Write in the writer's voice. No preamble, no advice, no therapist language. " +
            "Output only the reframed thought." +
            "<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n" +
            "Journal entry:\n\"$maskedText\"\n\n" +
            "$distortionContext" +
            "$evidenceBlock\n\n" +
            "$techniqueBlock" +
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
            label = CircumplexMapper.getLabel(vc, ac),
            source = AffectiveSource.REGEX,
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

        val recognized = mutableListOf<CognitiveDistortion>()
        val unrecognized = mutableListOf<String>()
        for (label in csv.split(",")) {
            val distortion = CognitiveDistortion.fromLabel(label)
            if (distortion != null) recognized.add(distortion)
            else unrecognized.add(label.trim())
        }
        if (unrecognized.isNotEmpty()) {
            Log.d(TAG, "parseDotOutput: unrecognized labels $unrecognized in csv=\"$csv\"")
        }
        return DiagnosisResult(distortions = recognized, reasoning = raw)
    }

    /**
     * Prepares the Stage 3 intervention and returns the raw [LlmResult] flow for
     * token-by-token streaming in the ViewModel. The caller is responsible for collecting
     * the flow and sealing to [Done][com.github.maskedkunisquat.lattice.ui.ReframeState.Done]
     * on [LlmResult.Complete].
     *
     * All prep work (strategy selection, BA activity lookup, evidence fetch) runs inside
     * [dispatcher] before the cold flow is returned.
     *
     * @return [Result.success] with the selected [ReframeStrategy] and the token flow,
     *   or [Result.failure] if prep fails (DAO error, no evidence source, etc.).
     */
    suspend fun streamStage3Intervention(
        maskedText: String,
        affectiveMap: AffectiveMapResult,
        diagnosis: DiagnosisResult,
    ): Result<Pair<ReframeStrategy, kotlinx.coroutines.flow.Flow<LlmResult>>> =
        withContext(dispatcher) {
            runCatching {
                val strategy = selectStrategy(affectiveMap.valence, affectiveMap.arousal)
                val baActivity = if (strategy == ReframeStrategy.BEHAVIORAL_ACTIVATION) {
                    pickBaActivity(maskedText)
                } else null
                val evidenceEntries = if (affectiveMap.valence < 0f) {
                    fetchEvidenceEntries(maskedText)
                } else emptyList()
                val flow = orchestrator.process(
                    buildInterventionPrompt(
                        maskedText, strategy, diagnosis.distortions, baActivity, evidenceEntries
                    ),
                    "intervention"
                )
                Pair(strategy, flow)
            }
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

    /** Source of the affective coordinates in Stage 1. */
    enum class AffectiveSource {
        /** Coordinates produced by the on-device [AffectiveMlp] head. */
        MLP,
        /** Coordinates parsed from the LLM's `v=<n> a=<n>` output via regex. */
        REGEX,
    }

    /**
     * Output of Stage 1. [valence] and [arousal] are clamped to [-1, 1].
     *
     * @param source Which path produced the coordinates — [AffectiveSource.MLP] when the
     *   trained head is available, [AffectiveSource.REGEX] when falling back to LLM output.
     */
    data class AffectiveMapResult(
        val valence: Float,
        val arousal: Float,
        val label: MoodLabel,
        val source: AffectiveSource = AffectiveSource.REGEX,
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
        private const val TAG = "ReframingLoop"
        /** Maximum activity difficulty included in the BA suggestion lookup (0–10 scale). */
        internal const val BA_MAX_DIFFICULTY = 5

        /**
         * Selects the intervention strategy based on circumplex quadrant.
         * v<0 and a≥0 → Quadrant II → [ReframeStrategy.SOCRATIC_REALITY_TESTING]
         * v<0 and a<0  → Quadrant III → [ReframeStrategy.BEHAVIORAL_ACTIVATION]
         * v≥0 (any a)  → Positive valence → [ReframeStrategy.STRENGTHS_AFFIRMATION]
         */
        fun selectStrategy(valence: Float, arousal: Float): ReframeStrategy = when {
            valence < 0f && arousal >= 0f -> ReframeStrategy.SOCRATIC_REALITY_TESTING
            valence < 0f && arousal < 0f  -> ReframeStrategy.BEHAVIORAL_ACTIVATION
            else                          -> ReframeStrategy.STRENGTHS_AFFIRMATION
        }
        // Both regexes tolerate optional spaces around `=` and an optional leading `-`.
        private val V_REGEX = Regex("""v\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val A_REGEX = Regex("""a\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        /** Matches [PERSON_UUID] placeholders produced by PiiShield. */
        private val PLACEHOLDER_REGEX = Regex("""\[PERSON_[a-fA-F0-9\-]{36}\]""")
    }
}
