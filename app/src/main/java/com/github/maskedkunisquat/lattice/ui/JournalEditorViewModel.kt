package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

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
)

class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    orchestrator: LlmOrchestrator,
    private val reframingLoop: ReframingLoop,
    private val transitEventDao: TransitEventDao,
) : ViewModel() {

    /** Drives the blue / amber privacy border in the UI. */
    val privacyState: StateFlow<PrivacyLevel> = orchestrator.privacyState

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var reframeJob: Job? = null
    /** ID of the most recently saved entry — set after [save] succeeds. */
    private var savedEntryId: UUID? = null

    /**
     * Called on every keystroke. Intercepts the [REFRAME_COMMAND] token anywhere in
     * [newText]: strips it from the displayed text and fires the reframe pipeline.
     */
    fun onTextChanged(newText: String) {
        if (REFRAME_COMMAND in newText) {
            val stripped = newText.replace(REFRAME_COMMAND, "").trim()
            _uiState.update { it.copy(text = stripped) }
            triggerReframe(stripped)
        } else {
            reframeJob?.cancel()
            _uiState.update { it.copy(text = newText, reframeState = ReframeState.Idle) }
        }
    }

    fun onMoodChanged(valence: Float, arousal: Float, label: MoodLabel) {
        _uiState.update { it.copy(valence = valence, arousal = arousal, label = label, moodSelected = true) }
    }

    fun save() {
        val state = _uiState.value
        val newId = UUID.randomUUID()
        savedEntryId = null  // invalidate any prior entry before the async write
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
     * Persists the accepted reframe to the already-saved entry and clears the result card.
     * Should only be called when [EditorUiState.reframeResult] is non-null and the entry
     * has been saved (i.e. [savedEntryId] is set). No new [TransitEvent] is logged here —
     * one was already written by the pipeline at generation time.
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
     *   - Exposes the Stage 3 reframe in [EditorUiState.reframeResult].
     *   - Writes a [TransitEvent] audit entry (local provider, no data left the device).
     *
     * On failure: surfaces the exception message in [EditorUiState.error].
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
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(reframeState = ReframeState.Error(e.message ?: "Reframe failed"))
                }
            }
        }
    }

    companion object {
        /** Command token typed by the user to trigger the reframing pipeline. */
        const val REFRAME_COMMAND = "!reframe"
        private const val REFRAME_PROVIDER  = "llama3_onnx_local"
        private const val REFRAME_OPERATION = "reframe"

        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalEditorViewModel(
                    journalRepository = app.journalRepository,
                    orchestrator      = app.llmOrchestrator,
                    reframingLoop     = app.reframingLoop,
                    transitEventDao   = app.database.transitEventDao(),
                ) as T
        }
    }
}
