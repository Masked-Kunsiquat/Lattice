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
import kotlinx.coroutines.launch
import java.util.UUID

class JournalEditorViewModel(
    private val journalRepository: JournalRepository,
    orchestrator: LlmOrchestrator,
) : ViewModel() {

    /** Drives the blue / amber privacy border in the UI. */
    val privacyState: StateFlow<PrivacyLevel> = orchestrator.privacyState

    private val _saved = MutableStateFlow(false)

    /** Pulses true after a successful save; call [resetSaved] to clear it. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun save(content: String, valence: Float, arousal: Float, label: MoodLabel) {
        viewModelScope.launch {
            journalRepository.saveEntry(
                JournalEntry(
                    id = UUID.randomUUID(),
                    timestamp = System.currentTimeMillis(),
                    content = content,
                    valence = valence,
                    arousal = arousal,
                    moodLabel = label.name, // recalculated by repository, but must be non-null
                    embedding = FloatArray(EmbeddingProvider.EMBEDDING_DIM),
                )
            )
            _saved.value = true
        }
    }

    fun resetSaved() {
        _saved.value = false
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalEditorViewModel(app.journalRepository, app.llmOrchestrator) as T
        }
    }
}
