package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.core.data.model.Tag
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel
import com.github.maskedkunisquat.lattice.core.logic.PeopleRepository
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import com.github.maskedkunisquat.lattice.core.logic.TagRepository
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed class MentionState {
    object Idle : MentionState()
    data class SuggestingPerson(val query: String, val results: List<Person>) : MentionState()
    data class SuggestingTag(val query: String, val results: List<Tag>) : MentionState()
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
)

class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    orchestrator: LlmOrchestrator,
    private val reframingLoop: ReframingLoop,
    private val transitEventDao: TransitEventDao,
    val modelLoadState: StateFlow<ModelLoadState>,
    private val peopleRepository: PeopleRepository,
    private val tagRepository: TagRepository,
) : ViewModel() {

    /** Drives the blue / amber privacy border in the UI. */
    val privacyState: StateFlow<PrivacyLevel> = orchestrator.privacyState

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var reframeJob: Job? = null
    /** ID of the most recently saved entry — set after [save] succeeds. */
    private var savedEntryId: UUID? = null

    /**
     * Called on every keystroke. Cancels any in-flight reframe job (stale if text changed)
     * and updates the displayed text.
     */
    fun onTextChanged(newText: String) {
        reframeJob?.cancel()
        _uiState.update { it.copy(text = newText, reframeState = ReframeState.Idle) }

        val personMatch = MENTION_REGEX.find(newText)
        val tagMatch    = TAG_REGEX.find(newText)
        when {
            personMatch != null -> {
                val query = personMatch.groupValues[1]
                viewModelScope.launch {
                    val results = peopleRepository.searchByName(query)
                    _uiState.update { it.copy(mentionState = MentionState.SuggestingPerson(query, results)) }
                }
            }
            tagMatch != null -> {
                val query = tagMatch.groupValues[1]
                viewModelScope.launch {
                    val results = tagRepository.searchTags(query)
                    _uiState.update { it.copy(mentionState = MentionState.SuggestingTag(query, results)) }
                }
            }
            else -> _uiState.update { it.copy(mentionState = MentionState.Idle) }
        }
    }

    fun onMentionSelected(person: Person) {
        val query = (_uiState.value.mentionState as? MentionState.SuggestingPerson)?.query ?: return
        val displayName = person.nickname ?: person.firstName
        val newText = _uiState.value.text.replace(Regex("@${Regex.escape(query)}$"), displayName)
        _uiState.update { it.copy(text = newText, mentionState = MentionState.Idle) }
    }

    fun onMentionCreateNew(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val person = Person(
                id = UUID.randomUUID(),
                firstName = name,
                lastName = null,
                nickname = null,
                relationshipType = RelationshipType.ACQUAINTANCE,
            )
            peopleRepository.insertPerson(person)
            onMentionSelected(person)
        }
    }

    fun onMentionDismiss() {
        _uiState.update { it.copy(mentionState = MentionState.Idle) }
    }

    fun onTagSelected(tag: Tag) {
        val query = (_uiState.value.mentionState as? MentionState.SuggestingTag)?.query ?: return
        val newText = _uiState.value.text.replace(Regex("#${Regex.escape(query)}$"), "#${tag.name}")
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
            val tag = tagRepository.insertTag(name)
            onTagSelected(tag)
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
        viewModelScope.launch {
            try {
                journalRepository.saveEntry(
                    JournalEntry(
                        id = newId,
                        timestamp = System.currentTimeMillis(),
                        content = state.text,
                        valence = state.valence,
                        arousal = state.arousal,
                        moodLabel = state.label.name,
                        embedding = FloatArray(EmbeddingProvider.EMBEDDING_DIM),
                        tagIds = tagIds,
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
        private val MENTION_REGEX  = Regex("@(\\w*)$")
        private val TAG_REGEX      = Regex("#(\\w*)$")
        /** Matches all resolved #tag tokens in saved text for tagId collection. */
        private val TAG_WORD_REGEX = Regex("#(\\w+)")

        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalEditorViewModel(
                    journalRepository = app.journalRepository,
                    orchestrator      = app.llmOrchestrator,
                    reframingLoop     = app.reframingLoop,
                    transitEventDao   = app.database.transitEventDao(),
                    modelLoadState    = app.localFallbackProvider.modelLoadState,
                    peopleRepository  = app.peopleRepository,
                    tagRepository     = app.tagRepository,
                ) as T
        }
    }
}
