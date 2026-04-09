package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeleteEvent(val entry: JournalEntry)

class JournalHistoryViewModel(
    private val journalRepository: JournalRepository,
) : ViewModel() {

    val entries: StateFlow<List<JournalEntry>> = journalRepository.getEntries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _deleteEvent = MutableSharedFlow<DeleteEvent>()
    val deleteEvent = _deleteEvent.asSharedFlow()

    fun deleteEntry(entry: JournalEntry) {
        viewModelScope.launch {
            journalRepository.deleteEntry(entry)
            _deleteEvent.emit(DeleteEvent(entry))
        }
    }

    fun undoDelete(entry: JournalEntry) {
        // entry.content is unmasked (from getEntries()). saveEntry re-masks it via PiiShield
        // using the same deterministic person-UUID scheme, so the stored form is identical.
        viewModelScope.launch { journalRepository.saveEntry(entry) }
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                JournalHistoryViewModel(journalRepository = app.journalRepository) as T
        }
    }
}
