package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LlmResult
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class EntryDetailViewModel(
    private val journalRepository: JournalRepository,
    private val reframingLoop: ReframingLoop,
    private val transitEventDao: TransitEventDao,
    val modelLoadState: StateFlow<ModelLoadState>,
    private val entryId: UUID,
) : ViewModel() {

    /** Live entry — content is unmasked for display via [JournalRepository.getEntryById]. */
    val entry: StateFlow<JournalEntry?> = journalRepository.getEntryById(entryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _reframeState = MutableStateFlow<ReframeState>(ReframeState.Idle)
    val reframeState: StateFlow<ReframeState> = _reframeState.asStateFlow()

    /** Emits once when the entry is deleted — screen should navigate back on receipt. */
    private val _deletedEvent = MutableSharedFlow<Unit>()
    val deletedEvent = _deletedEvent.asSharedFlow()

    private var reframeJob: Job? = null

    /**
     * Runs the three-stage reframe pipeline against the current entry content.
     * The unmasked display content is re-masked via [JournalRepository.maskText] before
     * reaching the LLM — PII never leaves the device in raw form.
     */
    fun requestReframe() {
        val content = entry.value?.content?.takeIf { it.isNotBlank() } ?: return
        reframeJob?.cancel()
        reframeJob = viewModelScope.launch {
            _reframeState.value = ReframeState.Loading
            try {
                val maskedText = journalRepository.maskText(content)

                val affectiveMap = reframingLoop.runStage1AffectiveMap(maskedText).getOrThrow()
                val diagnosis    = reframingLoop.runStage2DiagnosisOfThought(maskedText).getOrThrow()
                val (_, tokenFlow) = reframingLoop
                    .streamStage3Intervention(maskedText, affectiveMap, diagnosis)
                    .getOrThrow()

                var partial = ""
                tokenFlow.collect { result ->
                    when (result) {
                        is LlmResult.Token    -> {
                            partial += result.text
                            _reframeState.value = ReframeState.Streaming(partial)
                        }
                        is LlmResult.Complete -> Unit
                        is LlmResult.Error    -> throw result.cause
                    }
                }
                if (partial.isBlank()) throw IllegalStateException("Model returned an empty reframe.")
                _reframeState.value = ReframeState.Done(partial.trim())

                transitEventDao.insertEvent(
                    TransitEvent(
                        id            = UUID.randomUUID(),
                        timestamp     = System.currentTimeMillis(),
                        providerName  = "llama3_onnx_local",
                        operationType = "reframe",
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reframeState.value = ReframeState.Error(e.message ?: "Reframe failed")
            }
        }
    }

    /**
     * Persists the accepted reframe text to [JournalEntry.reframedContent].
     * No-op if [reframeState] is not [ReframeState.Done].
     */
    fun applyReframe() {
        val reframe = (_reframeState.value as? ReframeState.Done)?.text ?: return
        viewModelScope.launch {
            try {
                journalRepository.updateReframedContent(entryId.toString(), reframe)
                _reframeState.value = ReframeState.Idle
            } catch (e: Exception) {
                _reframeState.value = ReframeState.Error("Failed to save reframe: ${e.message}")
            }
        }
    }

    fun dismissReframe() {
        reframeJob?.cancel()
        _reframeState.value = ReframeState.Idle
    }

    fun deleteEntry() {
        val e = entry.value ?: return
        viewModelScope.launch {
            journalRepository.deleteEntry(e)
            _deletedEvent.emit(Unit)
        }
    }

    companion object {
        fun factory(app: LatticeApplication, entryId: UUID) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EntryDetailViewModel(
                        journalRepository = app.journalRepository,
                        reframingLoop     = app.reframingLoop,
                        transitEventDao   = app.database.transitEventDao(),
                        modelLoadState    = app.localFallbackProvider.modelLoadState,
                        entryId           = entryId,
                    ) as T
            }
    }
}
