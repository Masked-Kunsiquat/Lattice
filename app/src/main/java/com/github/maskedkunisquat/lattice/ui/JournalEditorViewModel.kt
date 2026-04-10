package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.core.data.model.Place
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel
import com.github.maskedkunisquat.lattice.core.logic.PeopleRepository
import com.github.maskedkunisquat.lattice.core.logic.PlaceRepository
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import com.github.maskedkunisquat.lattice.core.logic.TagRepository
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed class MentionState {
    object Idle : MentionState()
    data class SuggestingPerson(val query: String, val results: List<Person>) : MentionState()
    data class SuggestingTag(val query: String, val results: List<Tag>) : MentionState()
    data class SuggestingPlace(val query: String, val results: List<Place>) : MentionState()
}

sealed class ReframeState {
    object Idle                              : ReframeState()
    object Loading                           : ReframeState()
    data class Streaming(val partial: String): ReframeState()
    data class Done(val text: String)        : ReframeState()
    data class Error(val msg: String)        : ReframeState()
}

data class EditorUiState(
    val text: String = "",
    val valence: Float = 0f,
    val arousal: Float = 0f,
    val label: MoodLabel = MoodLabel.ALIVE,
    val moodSelected: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val reframeState: ReframeState = ReframeState.Idle,
    val mentionState: MentionState = MentionState.Idle,
    /** Tag name → UUID for tags resolved via # autocomplete in the current draft. */
    val resolvedTags: Map<String, UUID> = emptyMap(),
    /** Display name → UUID for persons resolved via @ autocomplete. Masking deferred to save(). */
    val resolvedPersons: Map<String, UUID> = emptyMap(),
    /** Place name → UUID for places resolved via ! autocomplete. Masking deferred to save(). */
    val resolvedPlaces: Map<String, UUID> = emptyMap(),
)

class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    orchestrator: LlmOrchestrator,
    private val reframingLoop: ReframingLoop,
    private val transitEventDao: TransitEventDao,
    val modelLoadState: StateFlow<ModelLoadState>,
    val copyProgress: StateFlow<Float>,
    private val peopleRepository: PeopleRepository,
    private val tagRepository: TagRepository,
    private val placeRepository: PlaceRepository,
    /** When non-null, loads the existing entry for viewing/editing on init. */
    private val initialEntryId: UUID? = null,
) : ViewModel() {

    /** Drives the blue / amber privacy border in the UI. */
    val privacyState: StateFlow<PrivacyLevel> = orchestrator.privacyState

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var reframeJob: Job? = null
    /** Tracks the active autocomplete lookup so stale results don't overwrite newer ones. */
    private var currentMentionJob: Job? = null
    /** ID of the most recently saved entry — set after [save] succeeds. */
    private var savedEntryId: UUID? = null

    init {
        if (initialEntryId != null) {
            viewModelScope.launch {
                val (entry, resolvedPersons, resolvedPlaces) =
                    journalRepository.getEntryForEditor(initialEntryId).firstOrNull()
                    ?: return@launch
                if (entry != null) {
                    _uiState.update {
                        it.copy(
                            text = entry.content ?: "",
                            valence = entry.valence,
                            arousal = entry.arousal,
                            label = runCatching { MoodLabel.valueOf(entry.moodLabel) }
                                .getOrDefault(MoodLabel.ALIVE),
                            moodSelected = true,
                            resolvedPersons = resolvedPersons,
                            resolvedPlaces = resolvedPlaces,
                        )
                    }
                }
            }
        }
    }

    /**
     * Called on every keystroke. Cancels any in-flight reframe job (stale if text changed)
     * and updates the displayed text.
     */
    fun onTextChanged(newText: String) {
        reframeJob?.cancel()
        _uiState.update { it.copy(text = newText, reframeState = ReframeState.Idle) }

        val personMatch = MENTION_REGEX.find(newText)
        val tagMatch    = TAG_REGEX.find(newText)
        val placeMatch  = PLACE_REGEX.find(newText)
        currentMentionJob?.cancel()
        when {
            personMatch != null -> {
                val query = personMatch.groupValues[1]
                currentMentionJob = viewModelScope.launch {
                    val results = peopleRepository.searchByName(query)
                    _uiState.update { it.copy(mentionState = MentionState.SuggestingPerson(query, results)) }
                }
            }
            tagMatch != null -> {
                val query = tagMatch.groupValues[1]
                currentMentionJob = viewModelScope.launch {
                    val results = tagRepository.searchTags(query)
                    _uiState.update { it.copy(mentionState = MentionState.SuggestingTag(query, results)) }
                }
            }
            placeMatch != null -> {
                val query = placeMatch.groupValues[1]
                currentMentionJob = viewModelScope.launch {
                    val results = placeRepository.searchPlaces(query)
                    _uiState.update { it.copy(mentionState = MentionState.SuggestingPlace(query, results)) }
                }
            }
            else -> {
                currentMentionJob = null
                _uiState.update { it.copy(mentionState = MentionState.Idle) }
            }
        }
    }

    fun onMentionSelected(person: Person) {
        val query = (_uiState.value.mentionState as? MentionState.SuggestingPerson)?.query ?: return
        val displayName = person.nickname
            ?: if (person.lastName != null) "${person.firstName} ${person.lastName}" else person.firstName
        val newText = _uiState.value.text.replace(Regex("@${Regex.escape(query)}$")) { "@$displayName" }
        _uiState.update {
            it.copy(
                text = newText,
                mentionState = MentionState.Idle,
                resolvedPersons = it.resolvedPersons + (displayName to person.id),
            )
        }
    }

    fun onMentionCreateNew(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                // Split "First Last" into first + last; everything after the first space is lastName.
                val parts = name.trim().split(" ", limit = 2)
                val person = Person(
                    id = UUID.randomUUID(),
                    firstName = parts[0],
                    lastName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    nickname = null,
                    relationshipType = RelationshipType.ACQUAINTANCE,
                )
                peopleRepository.insertPerson(person)
                onMentionSelected(person)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to create person") }
            }
        }
    }

    fun onMentionDismiss() {
        _uiState.update { it.copy(mentionState = MentionState.Idle) }
    }

    fun onTagSelected(tag: Tag) {
        val query = (_uiState.value.mentionState as? MentionState.SuggestingTag)?.query ?: return
        val tagToken = "#${tag.name}"
        val newText = _uiState.value.text.replace(Regex("#${Regex.escape(query)}$")) { tagToken }
        _uiState.update {
            it.copy(
                text = newText,
                mentionState = MentionState.Idle,
                resolvedTags = it.resolvedTags + (tag.name to tag.id),
            )
        }
    }

    fun onTagCreateNew(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val tag = tagRepository.insertTag(name)
                onTagSelected(tag)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to create tag") }
            }
        }
    }

    fun onPlaceSelected(place: Place) {
        val query = (_uiState.value.mentionState as? MentionState.SuggestingPlace)?.query ?: return
        // Keep the human-readable display form in the editor; masking to [PLACE_uuid] is deferred to save().
        val newText = _uiState.value.text.replace(Regex("!${Regex.escape(query)}$")) { "!${place.name}" }
        _uiState.update {
            it.copy(
                text = newText,
                mentionState = MentionState.Idle,
                resolvedPlaces = it.resolvedPlaces + (place.name to place.id),
            )
        }
    }

    fun onPlaceCreateNew(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val place = placeRepository.insertPlace(name)
                onPlaceSelected(place)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to create place") }
            }
        }
    }

    /**
     * Fires the reframe pipeline against the current editor text.
     * Called by the Reframe button in [JournalEditorScreen] — replaces the former
     * `!reframe` text-command model. Text is passed to [triggerReframe] as-is;
     * no stripping or command extraction occurs.
     */
    fun requestReframe() {
        triggerReframe(_uiState.value.text)
    }

    fun onMoodChanged(valence: Float, arousal: Float, label: MoodLabel) {
        _uiState.update { it.copy(valence = valence, arousal = arousal, label = label, moodSelected = true) }
    }

    fun save() {
        val state = _uiState.value
        val newId = UUID.randomUUID()
        savedEntryId = null  // invalidate any prior entry before the async write
        // Collect UUIDs for #tag tokens still present in the text
        val tagIds = TAG_WORD_REGEX.findAll(state.text)
            .mapNotNull { state.resolvedTags[it.groupValues[1]] }
            .distinct()
            .toList()
        // Collect placeIds from resolved map — no sentinel parsing needed since
        // onPlaceSelected now keeps !name display form in text until save.
        val placeIds = state.resolvedPlaces.values.distinct().toList()

        // Substitute display-form mentions with PII sentinels before handing off to
        // JournalRepository (which will further mask any remaining plain-text names via PiiShield).
        val content = if (state.text.isBlank()) null else {
            var masked = state.text
            // Longest display names first to avoid "Jo" shadowing "John"
            state.resolvedPersons.entries
                .sortedByDescending { it.key.length }
                .forEach { (displayName, uuid) ->
                    masked = masked.replace(Regex("@${Regex.escape(displayName)}"), "[PERSON_$uuid]")
                }
            state.resolvedPlaces.entries
                .sortedByDescending { it.key.length }
                .forEach { (name, uuid) ->
                    masked = masked.replace(Regex("!${Regex.escape(name)}"), "[PLACE_$uuid]")
                }
            masked
        }

        viewModelScope.launch {
            try {
                journalRepository.saveEntry(
                    JournalEntry(
                        id = newId,
                        timestamp = System.currentTimeMillis(),
                        content = content,
                        valence = state.valence,
                        arousal = state.arousal,
                        moodLabel = state.label.name,
                        embedding = FloatArray(EmbeddingProvider.EMBEDDING_DIM),
                        tagIds = tagIds,
                        placeIds = placeIds,
                    )
                )
                savedEntryId = newId
                _uiState.update { it.copy(saved = true) }
            } catch (e: Exception) {
                savedEntryId = null
                _uiState.update { it.copy(error = e.message ?: "Failed to save entry") }
            }
        }
    }

    fun resetSaved() {
        savedEntryId = null
        _uiState.update { EditorUiState() }
    }

    /**
     * Persists the accepted reframe to the already-saved entry and transitions
     * [EditorUiState.reframeState] back to [ReframeState.Idle].
     * No-op when [reframeState] is not [ReframeState.Done] or [savedEntryId] is unset.
     * No new [TransitEvent] is logged here — one was already written by the pipeline at
     * generation time.
     */
    fun applyReframe() {
        val entryId = savedEntryId ?: return
        val reframe = (_uiState.value.reframeState as? ReframeState.Done)?.text ?: return
        viewModelScope.launch {
            try {
                journalRepository.updateReframedContent(entryId.toString(), reframe)
                _uiState.update { it.copy(reframeState = ReframeState.Idle) }
            } catch (e: Throwable) {
                _uiState.update { it.copy(error = "Failed to save reframe: ${e.message}") }
            }
        }
    }

    /** Resets the reframe state to [ReframeState.Idle] without writing to the DB. */
    fun dismissReframe() {
        _uiState.update { it.copy(reframeState = ReframeState.Idle) }
    }

    // ── Reframe pipeline ──────────────────────────────────────────────────────

    /**
     * Runs the three-stage [ReframingLoop] pipeline against [text] (already stripped
     * of the `!reframe` command). The text is PII-masked before any prompt is sent.
     *
     * On success:
     *   - Updates mood coordinates from Stage 1's affective map.
     *   - Transitions [EditorUiState.reframeState] to [ReframeState.Done] with the
     *     Stage 3 reframe streamed token-by-token through [ReframeState.Streaming].
     *   - Writes a [TransitEvent] audit entry (local provider, no data left the device).
     *
     * On failure: transitions [EditorUiState.reframeState] to [ReframeState.Error].
     * On cancellation (user typed again): re-throws [CancellationException] — state
     * was already reset to [ReframeState.Idle] by [onTextChanged].
     */
    private fun triggerReframe(text: String) {
        if (text.isBlank()) return
        reframeJob?.cancel()
        reframeJob = viewModelScope.launch {
            _uiState.update { it.copy(reframeState = ReframeState.Loading, error = null) }
            try {
                // Mask PII before any text reaches the LLM
                val maskedText = journalRepository.maskText(text)

                // Stage 1 — Affective Mapping
                val affectiveMap = reframingLoop
                    .runStage1AffectiveMap(maskedText)
                    .getOrThrow()

                // Stage 2 — Diagnosis of Thought
                val diagnosis = reframingLoop
                    .runStage2DiagnosisOfThought(maskedText)
                    .getOrThrow()

                // Stage 3 — Stream tokens into Streaming state, seal to Done on Complete
                val (_, tokenFlow) = reframingLoop
                    .streamStage3Intervention(maskedText, affectiveMap, diagnosis)
                    .getOrThrow()

                var partial = ""
                tokenFlow.collect { result ->
                    when (result) {
                        is LlmResult.Token -> {
                            partial += result.text
                            _uiState.update { it.copy(reframeState = ReframeState.Streaming(partial)) }
                        }
                        is LlmResult.Complete -> Unit
                        is LlmResult.Error    -> throw result.cause
                    }
                }

                if (partial.isBlank()) throw IllegalStateException("Model returned an empty reframe.")

                _uiState.update {
                    it.copy(
                        reframeState = ReframeState.Done(partial.trim()),
                        valence      = affectiveMap.valence,
                        arousal      = affectiveMap.arousal,
                        label        = affectiveMap.label,
                        moodSelected = true,
                    )
                }

                // Audit trail: local reframe invocation (no data left the device)
                transitEventDao.insertEvent(
                    TransitEvent(
                        id            = UUID.randomUUID(),
                        timestamp     = System.currentTimeMillis(),
                        providerName  = REFRAME_PROVIDER,
                        operationType = REFRAME_OPERATION,
                    )
                )
            } catch (e: CancellationException) {
                throw e  // job was cancelled by the user — do not surface as an error
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(reframeState = ReframeState.Error(e.message ?: "Reframe failed"))
                }
            }
        }
    }

    companion object {
        private const val REFRAME_PROVIDER  = "llama3_onnx_local"
        private const val REFRAME_OPERATION = "reframe"
        // Allow spaces in person/place names (e.g. "@John Smith", "!Central Park").
        // Pattern ends at a word char (no trailing space) so selecting a suggestion and
        // typing a space naturally dismisses the autocomplete without re-triggering it.
        // [\p{L}\p{N}_] instead of \w so accented/diacritic names (André, José) work.
        private val W = "[\\p{L}\\p{N}_]"
        private val MENTION_REGEX  = Regex("@((?:$W+ )*$W+|$W*)$")
        private val TAG_REGEX      = Regex("#($W*)$")
        private val PLACE_REGEX    = Regex("!((?:$W+ )*$W+|$W*)$")
        /** Matches all resolved #tag tokens in saved text for tagId collection. */
        private val TAG_WORD_REGEX = Regex("#($W+)")

        fun factory(app: LatticeApplication, initialEntryId: UUID? = null) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    JournalEditorViewModel(
                        journalRepository = app.journalRepository,
                        orchestrator      = app.llmOrchestrator,
                        reframingLoop     = app.reframingLoop,
                        transitEventDao   = app.database.transitEventDao(),
                        modelLoadState    = app.localFallbackProvider.modelLoadState,
                        copyProgress      = app.localFallbackProvider.copyProgress,
                        peopleRepository  = app.peopleRepository,
                        tagRepository     = app.tagRepository,
                        placeRepository   = app.placeRepository,
                        initialEntryId    = initialEntryId,
                    ) as T
            }
    }
}
