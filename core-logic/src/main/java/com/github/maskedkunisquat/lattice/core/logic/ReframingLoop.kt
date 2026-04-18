package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.Place
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
    @Volatile var affectiveMlp: AffectiveMlp? = null,
    @Volatile var distortionMlp: DistortionMlp? = null,
    private val logger: Logger = object : Logger {
        override fun debug(tag: String, msg: String) = Unit
        override fun info(tag: String, msg: String) = Unit
        override fun warn(tag: String, msg: String, throwable: Throwable?) = Unit
        override fun error(tag: String, msg: String, throwable: Throwable?) = Unit
    },
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
                        logger.debug(TAG, "Stage1: source=mlp")
                        AffectiveMapResult(
                            valence = vc,
                            arousal = ac,
                            label = CircumplexMapper.getLabel(vc, ac),
                            source = AffectiveSource.MLP,
                        )
                    }.onFailure { e ->
                        logger.warn(TAG, "Stage1: MLP path threw, falling back to regex", e)
                    }.getOrNull()
                } else null

                mlpResult ?: run {
                    val raw = collectTokens(
                        orchestrator.process(
                            buildAffectivePrompt(maskedText),
                            "affective_map",
                            AFFECTIVE_SYSTEM,
                        )
                    )
                    logger.debug(TAG, "Stage1: source=regex")
                    parseAffectiveCoords(raw)
                }
            }
        }
    }

    // ── Stage 2 ──────────────────────────────────────────────────────────────

    /**
     * Diagnosis of Thought: classifies which of the 12 CBT [CognitiveDistortion]s are
     * present in the journal text.
     *
     * The model is asked to output only the `DISTORTIONS:` sentinel line — no chain-of-
     * thought reasoning. This keeps latency low on small models (Gemma 3 1B) that tend
     * to produce verbose, poorly-formatted reasoning when given multi-step instructions.
     *
     * @param maskedText PII-masked journal text.
     * @return [Result.success] with [DiagnosisResult], or [Result.failure] on model
     *   error or unparseable output (missing sentinel).
     */
    suspend fun runStage2DiagnosisOfThought(maskedText: String): Result<DiagnosisResult> =
        withContext(dispatcher) {
            runCatching {
                val mlp     = distortionMlp
                val embedder = embeddingProvider
                val mlpResult: DiagnosisResult? = if (mlp != null && embedder != null) {
                    runCatching {
                        val embedding  = embedder.generateEmbedding(maskedText)
                        val labels     = mlp.forward(embedding)
                        require(labels.size >= CognitiveDistortion.entries.size) {
                            "MLP output size ${labels.size} < expected ${CognitiveDistortion.entries.size}"
                        }
                        val distortions = CognitiveDistortion.entries.filterIndexed { i, _ -> labels[i] }
                        logger.debug(TAG, "Stage2: source=mlp, distortions=$distortions")
                        DiagnosisResult(
                            distortions = distortions,
                            reasoning   = "MLP classifier (${distortions.size} classes active)",
                            source      = DiagnosisSource.MLP,
                        )
                    }.onFailure { e ->
                        logger.warn(TAG, "Stage2: MLP path threw, falling back to LLM", e)
                    }.getOrNull()
                } else null

                mlpResult ?: run {
                    val raw = collectTokens(
                        orchestrator.process(buildDotPrompt(maskedText), "dot_diagnosis", DOT_SYSTEM)
                    )
                    parseDotOutput(raw)
                }
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
        personById: Map<java.util.UUID, Person> = emptyMap(),
        placeById: Map<java.util.UUID, Place> = emptyMap(),
    ): Result<ReframeResult> = withContext(dispatcher) {
        runCatching {
            val strategy = selectStrategy(affectiveMap.valence, affectiveMap.arousal)
            val displayText = buildDisplayText(maskedText, personById, placeById)

            // Distortion gate: REFLECTION entries with no detected distortions have nothing to
            // reframe. Skip the LLM entirely and return a retrieval card instead.
            if (strategy == ReframeStrategy.REFLECTION && diagnosis.distortions.isEmpty()) {
                val evidence = fetchReflectionEvidence(maskedText)
                val card = buildReflectionCard(displayText, evidence, personById, placeById)
                return@runCatching ReframeResult(strategy = ReframeStrategy.REFLECTION_CARD, reframe = card)
            }

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
                        displayText, strategy, diagnosis.distortions, baActivity, evidenceEntries
                    ),
                    "intervention",
                    INTERVENTION_SYSTEM,
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
        "Analyze the emotional content of the following text.\n" +
        "Output ONLY one line in this exact format: v=<number> a=<number>\n" +
        "  v = valence  : -1.0 (very negative) to 1.0 (very positive)\n" +
        "  a = arousal  : -1.0 (calm / passive) to 1.0 (excited / active)\n" +
        "Numbers must be between -1.0 and 1.0. No other text.\n\n" +
        "Text: $maskedText"

    internal fun buildDotPrompt(maskedText: String): String =
        "Identify any cognitive distortions in the text below.\n" +
        "Only use names from this list: ${CognitiveDistortion.promptList}\n\n" +
        "Respond with only this line (no explanation, no markdown):\n" +
        "DISTORTIONS: <comma-separated names from the list, or NONE>\n\n" +
        "Text: $maskedText\n\n" +
        "FINAL_DISTORTIONS:"


    internal fun buildInterventionPrompt(
        maskedText: String,
        strategy: ReframeStrategy,
        distortions: List<CognitiveDistortion>,
        baActivity: ActivityHierarchy? = null,
        evidenceEntries: List<JournalEntry> = emptyList(),
    ): String {
        val distortionLine = if (distortions.isEmpty()) ""
        else "Distortions present: ${distortions.joinToString(", ") { it.label }}\n"

        val evidenceBullets = evidenceEntries.mapNotNull { entry ->
            val snippet = entry.content?.takeIf { it.isNotBlank() }?.let {
                if (it.length > 150) it.take(150) + "…" else it
            } ?: return@mapNotNull null
            "- $snippet"
        }
        val evidenceBlock = if (evidenceBullets.isNotEmpty()) {
            "Past evidence that contradicts this belief:\n${evidenceBullets.joinToString("\n")}\n\n"
        } else ""

        val techniqueBlock = when (strategy) {
            ReframeStrategy.SOCRATIC_REALITY_TESTING ->
                "Question whether the fear or assumption is definitely true, then land on a more balanced reading."

            ReframeStrategy.BEHAVIORAL_ACTIVATION -> {
                val actionStep = if (baActivity != null)
                    " End with this one concrete next step: \"${baActivity.taskName}\"."
                else
                    " End with one specific, minimal action that addresses what the entry actually describes."
                "Name the low-energy or avoidance pattern as temporary, not a fixed trait.$actionStep"
            }

            ReframeStrategy.REFLECTION ->
                "Notice what this entry reveals about what matters to you — name the value or relationship it points to."

            ReframeStrategy.STRENGTHS_AFFIRMATION ->
                "Name the strength or effort this entry shows and connect it to what matters to you."

            ReframeStrategy.REFLECTION_CARD ->
                throw IllegalArgumentException("buildInterventionPrompt must not be called for REFLECTION_CARD — use buildReflectionCard instead.")
        }

        return "Journal entry: \"$maskedText\"\n\n" +
            distortionLine +
            evidenceBlock +
            "$techniqueBlock\n\n" +
            "Output only the reframe."
    }

    /**
     * Replaces UUID placeholders in [maskedText] with pseudonymous display names for use
     * in Stage 3 prompts only. `[PERSON_uuid]` → `@{nickname ?: firstName}`,
     * `[PLACE_uuid]` → `!{placeName}`. Tokens whose UUID is absent from the lookup maps
     * are left unchanged so the model still sees a coherent (if opaque) token.
     *
     * This function must never be called for Stage 1/2 inputs, evidence retrieval, or storage.
     */
    internal fun buildDisplayText(
        maskedText: String,
        personById: Map<java.util.UUID, Person>,
        placeById: Map<java.util.UUID, Place>,
    ): String {
        if (personById.isEmpty() && placeById.isEmpty()) return maskedText
        var result = PLACEHOLDER_REGEX.replace(maskedText) { match ->
            val uuidStr = match.value.removePrefix("[PERSON_").removeSuffix("]")
            val uuid = runCatching { java.util.UUID.fromString(uuidStr) }.getOrNull()
            val person = uuid?.let { personById[it] }
            if (person != null) "@${person.nickname ?: person.firstName}" else match.value
        }
        result = PLACE_PLACEHOLDER_REGEX.replace(result) { match ->
            val uuidStr = match.value.removePrefix("[PLACE_").removeSuffix("]")
            val uuid = runCatching { java.util.UUID.fromString(uuidStr) }.getOrNull()
            val place = uuid?.let { placeById[it] }
            if (place != null) "!${place.name}" else match.value
        }
        return result
    }

    /**
     * Builds a retrieval card for undistorted REFLECTION entries — no LLM generation.
     *
     * Extracts `@name` and `!place` tokens from [displayText] (already substituted by
     * [buildDisplayText]), then assembles a card from [evidenceEntries] (at most 2 snippets,
     * each unmasked via [buildDisplayText]). Falls back to a single templated line when no
     * evidence is available.
     */
    internal fun buildReflectionCard(
        displayText: String,
        evidenceEntries: List<JournalEntry>,
        personById: Map<java.util.UUID, Person>,
        placeById: Map<java.util.UUID, Place>,
    ): String {
        val atNames  = Regex("""@(\S+)""").findAll(displayText).map { "@${it.groupValues[1]}" }.toList()
        val bangNames = Regex("""!(\S+)""").findAll(displayText).map { "!${it.groupValues[1]}" }.toList()
        val entities  = (atNames + bangNames).distinct()
        val entityList = when {
            entities.isEmpty() -> "these connections"
            entities.size == 1 -> entities[0]
            else               -> entities.dropLast(1).joinToString(", ") + " and " + entities.last()
        }

        val snippets = evidenceEntries.take(2).mapNotNull { entry ->
            val content = entry.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val display = buildDisplayText(content, personById, placeById)
            val trimmed = if (display.length > 120) display.take(120) + "…" else display
            "\"$trimmed\""
        }

        return if (snippets.isNotEmpty()) {
            "You've written about $entityList before:\n${snippets.joinToString("\n")}\n" +
            "Connection to $entityList keeps showing up in what you write."
        } else {
            "Spending time with $entityList is something that matters to you."
        }
    }

    /**
     * Fetches recent journal entries referencing the same entity placeholders as the current
     * entry, with no valence filter. Used for the [ReframeStrategy.REFLECTION_CARD] path.
     */
    private suspend fun fetchReflectionEvidence(maskedText: String): List<JournalEntry> {
        val repo = searchRepository ?: return emptyList()
        val placeholders = (
            PLACEHOLDER_REGEX.findAll(maskedText) +
            PLACE_PLACEHOLDER_REGEX.findAll(maskedText)
        ).map { it.value }.toSet()
        return repo.findRecentEntriesForEntities(placeholders)
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
     * Finds the last `DISTORTIONS:` sentinel line in [raw], splits the CSV, and resolves
     * each token to a [CognitiveDistortion]. Unrecognised tokens are silently dropped.
     *
     * If the sentinel is absent, returns an empty distortion list rather than attempting
     * any greedy inference — callers should treat a missing sentinel as an unparseable response.
     */
    internal fun parseDotOutput(raw: String): DiagnosisResult {
        // Prefer the unique FINAL_DISTORTIONS: sentinel (avoids confusion with the
        // instruction-format line); fall back to legacy DISTORTIONS: for robustness.
        val lines = raw.lines()
        val sentinelLine = lines.lastOrNull { it.contains("FINAL_DISTORTIONS:", ignoreCase = true) }
            ?: lines.lastOrNull { it.contains("DISTORTIONS:", ignoreCase = true) }

        if (sentinelLine == null) {
            logger.debug(TAG, "parseDotOutput: no sentinel in output — returning empty distortions")
            return DiagnosisResult(distortions = emptyList(), reasoning = raw)
        }

        val sentinel = if (sentinelLine.contains("FINAL_DISTORTIONS:", ignoreCase = true)) "FINAL_DISTORTIONS:" else "DISTORTIONS:"
        val colonIdx = sentinelLine.indexOf(sentinel, ignoreCase = true)
        val csv = sentinelLine.substring(colonIdx + sentinel.length).trim()

        if (csv.equals("NONE", ignoreCase = true) || csv.isEmpty()) {
            return DiagnosisResult(distortions = emptyList(), reasoning = raw)
        }

        val commaTokens = csv.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val fromCommas = commaTokens.mapNotNull { CognitiveDistortion.fromLabel(it) }
        val unrecognized = commaTokens.filter { CognitiveDistortion.fromLabel(it) == null }
        if (unrecognized.isNotEmpty()) {
            logger.debug(TAG, "parseDotOutput: unrecognized tokens $unrecognized in csv=\"$csv\"")
        }
        return DiagnosisResult(distortions = fromCommas, reasoning = raw)
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
        personById: Map<java.util.UUID, Person> = emptyMap(),
        placeById: Map<java.util.UUID, Place> = emptyMap(),
    ): Result<Pair<ReframeStrategy, kotlinx.coroutines.flow.Flow<LlmResult>>> =
        withContext(dispatcher) {
            runCatching {
                val strategy = selectStrategy(affectiveMap.valence, affectiveMap.arousal)
                val displayText = buildDisplayText(maskedText, personById, placeById)

                // Distortion gate: REFLECTION + no distortions → retrieval card, no LLM call.
                if (strategy == ReframeStrategy.REFLECTION && diagnosis.distortions.isEmpty()) {
                    val evidence = fetchReflectionEvidence(maskedText)
                    val card = buildReflectionCard(displayText, evidence, personById, placeById)
                    val cardFlow = kotlinx.coroutines.flow.flow<LlmResult> {
                        emit(LlmResult.Token(card))
                        emit(LlmResult.Complete)
                    }
                    return@runCatching Pair(ReframeStrategy.REFLECTION_CARD, cardFlow)
                }

                val baActivity = if (strategy == ReframeStrategy.BEHAVIORAL_ACTIVATION) {
                    pickBaActivity(maskedText)
                } else null
                val evidenceEntries = if (affectiveMap.valence < 0f) {
                    fetchEvidenceEntries(maskedText)
                } else emptyList()
                val flow = orchestrator.process(
                    buildInterventionPrompt(
                        displayText, strategy, diagnosis.distortions, baActivity, evidenceEntries
                    ),
                    "intervention",
                    INTERVENTION_SYSTEM,
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

    /** Source of the distortion classification in Stage 2. */
    enum class DiagnosisSource {
        /** Distortions produced by the on-device [DistortionMlp] head. */
        MLP,
        /** Distortions parsed from the LLM's `DISTORTIONS:` sentinel output. */
        LLM,
    }

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
        val reasoning: String,
        val source: DiagnosisSource = DiagnosisSource.LLM,
    )

    /**
     * The CBT intervention strategy selected by Stage 3, derived from the circumplex quadrant.
     */
    enum class ReframeStrategy {
        /** Quadrant II (v<0, a≥0): Socratic questioning + Reality Testing + probability. */
        SOCRATIC_REALITY_TESTING,
        /** Quadrant III (v<0, a<0): Behavioral Activation + Evidence for the Contrary. */
        BEHAVIORAL_ACTIVATION,
        /** Low-positive band (0 ≤ v < AFFIRMATION_THRESHOLD): Reflective awareness of what matters. */
        REFLECTION,
        /** High-positive band (v ≥ AFFIRMATION_THRESHOLD): Strengths affirmation for clearly positive entries. */
        STRENGTHS_AFFIRMATION,
        /**
         * Undistorted REFLECTION entry — assembled from entity-anchored retrieval, no LLM generation.
         * Returned when [strategy] == [REFLECTION] and distortions are empty; the [ReframeResult.reframe]
         * string contains a retrieval card rather than generated text.
         */
        REFLECTION_CARD,
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
         * Minimum valence for [ReframeStrategy.STRENGTHS_AFFIRMATION]. Entries with valence in
         * [0, AFFIRMATION_THRESHOLD) are routed to [ReframeStrategy.REFLECTION] instead.
         * Tune this value to adjust how much of the positive-valence band gets reflective vs.
         * strengths-focused framing.
         */
        internal const val AFFIRMATION_THRESHOLD = 0.4f

        internal const val AFFECTIVE_SYSTEM =
            "You are an affective computing assistant. " +
            "Analyze text and output emotional coordinates only. " +
            "Never include explanations or additional text."

        internal const val DOT_SYSTEM =
            "You are a CBT thought classifier. Identify cognitive distortions concisely. " +
            "Output only the DISTORTIONS line. No explanation, no markdown."

        internal const val INTERVENTION_SYSTEM =
            "You are a CBT journaling assistant. " +
            "Write exactly 2-3 sentences as a brief, grounded, first-person reframe. " +
            "Interpret the entry literally — do not contradict what it says or invent details not in the text. " +
            "Never repeat or amplify the negative thought. " +
            "No motivational cheerleading. " +
            "Write in first person singular only (I, me, my). Never use 'we', 'let's', or 'you'. " +
            "No markdown, no asterisks, no ellipses, no therapist language."

        /**
         * Selects the intervention strategy based on circumplex quadrant and valence band.
         * v<0 and a≥0              → Quadrant II   → [ReframeStrategy.SOCRATIC_REALITY_TESTING]
         * v<0 and a<0              → Quadrant III  → [ReframeStrategy.BEHAVIORAL_ACTIVATION]
         * 0 ≤ v < AFFIRMATION_THRESHOLD (any a) → [ReframeStrategy.REFLECTION]
         * v ≥ AFFIRMATION_THRESHOLD (any a)     → [ReframeStrategy.STRENGTHS_AFFIRMATION]
         */
        fun selectStrategy(valence: Float, arousal: Float): ReframeStrategy = when {
            valence < 0f && arousal >= 0f        -> ReframeStrategy.SOCRATIC_REALITY_TESTING
            valence < 0f && arousal < 0f         -> ReframeStrategy.BEHAVIORAL_ACTIVATION
            valence < AFFIRMATION_THRESHOLD      -> ReframeStrategy.REFLECTION
            else                                 -> ReframeStrategy.STRENGTHS_AFFIRMATION
        }
        // Both regexes tolerate optional spaces around `=` and an optional leading `-`.
        private val V_REGEX = Regex("""v\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val A_REGEX = Regex("""a\s*=\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        /** Matches [PERSON_UUID] placeholders produced by PiiShield. */
        private val PLACEHOLDER_REGEX = Regex("""\[PERSON_[a-fA-F0-9\-]{36}\]""")
        /** Matches [PLACE_UUID] placeholders produced by PiiShield. */
        private val PLACE_PLACEHOLDER_REGEX = Regex("""\[PLACE_[a-fA-F0-9\-]{36}\]""")
    }
}
