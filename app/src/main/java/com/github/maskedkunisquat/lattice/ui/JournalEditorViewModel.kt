package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class EditorUiState(
    val text: String = "",
    val valence: Float = 0f,
    val arousal: Float = 0f,
    val label: MoodLabel = MoodLabel.ALIVE,
    val moodSelected: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)

class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    orchestrator: LlmOrchestrator,
) : ViewModel() {

    /** Drives the blue / amber privacy border in the UI. */
    val privacyState: StateFlow<PrivacyLevel> = orchestrator.privacyState

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun onTextChanged(text: String) {
        _uiState.update { it.copy(text = text) }
    }

    fun onMoodChanged(valence: Float, arousal: Float, label: MoodLabel) {
        _uiState.update { it.copy(valence = valence, arousal = arousal, label = label, moodSelected = true) }
    }

    fun save() {
        val state = _uiState.value
        viewModelScope.launch {
            try {
                journalRepository.saveEntry(
                    JournalEntry(
                        id = UUID.randomUUID(),
                        timestamp = System.currentTimeMillis(),
                        content = state.text,
                        valence = state.valence,
                        arousal = state.arousal,
                        moodLabel = state.label.name, // recalculated by repository, but must be non-null
                        embedding = FloatArray(EmbeddingProvider.EMBEDDING_DIM),
                    )
                )
                _uiState.update { it.copy(saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to save entry") }
            }
        }
    }

    fun resetSaved() {
        _uiState.update { EditorUiState() }
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalEditorViewModel(app.journalRepository, app.llmOrchestrator) as T
        }
    }
}
