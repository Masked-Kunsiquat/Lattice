package com.github.maskedkunisquat.lattice.ui

import android.util.Log
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed class EntryDetailState {
    object Loading : EntryDetailState()
    data class Found(val entry: JournalEntry) : EntryDetailState()
    object NotFound : EntryDetailState()
}

class EntryDetailViewModel(
    private val journalRepository: JournalRepository,
    private val reframingLoop: ReframingLoop,
    private val transitEventDao: TransitEventDao,
    val modelLoadState: StateFlow<ModelLoadState>,
    private val entryId: UUID,
) : ViewModel() {

    /** Live entry state — distinguishes initial loading from a missing entry. */
    val entryState: StateFlow<EntryDetailState> = journalRepository.getEntryById(entryId)
        .map { entry -> if (entry != null) EntryDetailState.Found(entry) else EntryDetailState.NotFound }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryDetailState.Loading)

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
        val content = (entryState.value as? EntryDetailState.Found)
            ?.entry?.content?.takeIf { it.isNotBlank() } ?: return
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

                runCatching {
                    transitEventDao.insertEvent(
                        TransitEvent(
                            id            = UUID.randomUUID(),
                            timestamp     = System.currentTimeMillis(),
                            providerName  = "llama3_onnx_local",
                            operationType = "reframe",
                        )
                    )
                }.onFailure { e ->
                    Log.w(TAG, "Failed to insert TransitEvent — reframe result unaffected", e)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reframeState.value = ReframeState.Error(e.message ?: "Reframe failed")
            }
        }
    }

    /**
     * Persists the reframe text the user accepted, capturing whether they edited it.
     *
     * Compares [editedText] to the model's original output ([ReframeState.Done.text]);
     * sets [JournalEntry.reframeEditedByUser] to `true` if the trimmed strings differ.
     * No-op if [reframeState] is not [ReframeState.Done] or the entry is not loaded.
     */
    fun acceptReframe(editedText: String) {
        val original = (_reframeState.value as? ReframeState.Done)?.text ?: return
        val entry = (entryState.value as? EntryDetailState.Found)?.entry ?: return
        val editedNormalized = editedText.trim()
        val edited = editedNormalized != original.trim()
        viewModelScope.launch {
            try {
                val maskedReframe = journalRepository.maskText(editedNormalized)
                journalRepository.updateEntry(
                    entry.copy(
                        reframedContent = maskedReframe,
                        reframeEditedByUser = edited,
                    )
                )
                _reframeState.value = ReframeState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reframeState.value = ReframeState.Error("Failed to save reframe: ${e.message}")
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
                val maskedReframe = journalRepository.maskText(reframe)
                journalRepository.updateReframedContent(entryId.toString(), maskedReframe)
                _reframeState.value = ReframeState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reframeState.value = ReframeState.Error("Failed to save reframe: ${e.message}")
            }
        }
    }

    /**
     * Persists the user's mood coordinates from the circumplex grid ("How does this land?").
     * No-op if the entry is not loaded. Coordinates remain null if the user skips the grid.
     */
    fun confirmMoodCoordinates(v: Float, a: Float) {
        val entry = (entryState.value as? EntryDetailState.Found)?.entry ?: return
        viewModelScope.launch {
            try {
                val clampedValence = v.coerceIn(-1f, 1f)
                val clampedArousal = a.coerceIn(-1f, 1f)
                journalRepository.updateEntry(entry.copy(userValence = clampedValence, userArousal = clampedArousal))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save mood coordinates", e)
            }
        }
    }

    fun dismissReframe() {
        reframeJob?.cancel()
        _reframeState.value = ReframeState.Idle
    }

    fun deleteEntry() {
        val e = (entryState.value as? EntryDetailState.Found)?.entry ?: return
        viewModelScope.launch {
            try {
                journalRepository.deleteEntry(e)
                _deletedEvent.emit(Unit)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to delete entry ${e.id}", ex)
            }
        }
    }

    companion object {
        private const val TAG = "EntryDetailViewModel"

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
