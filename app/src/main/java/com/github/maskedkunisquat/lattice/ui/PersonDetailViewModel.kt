package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.PeopleRepository
import com.github.maskedkunisquat.lattice.core.logic.PersonWithPhones
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed class PersonDetailState {
    object Loading : PersonDetailState()
    object NotFound : PersonDetailState()
    data class Found(
        val personWithPhones: PersonWithPhones,
        val entries: List<JournalEntry>,
    ) : PersonDetailState()
}

class PersonDetailViewModel(
    private val personId: UUID,
    private val peopleRepository: PeopleRepository,
    private val journalRepository: JournalRepository,
) : ViewModel() {

    val state: StateFlow<PersonDetailState> = combine(
        peopleRepository.getPersonWithPhones(personId),
        journalRepository.getEntriesForPerson(personId),
    ) { pwp, entries ->
        pwp ?: return@combine PersonDetailState.NotFound
        PersonDetailState.Found(personWithPhones = pwp, entries = entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonDetailState.Loading)

    private val _deletedEvent = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deletedEvent = _deletedEvent.asSharedFlow()

    fun deletePerson() {
        viewModelScope.launch {
            peopleRepository.deletePerson(personId)
            _deletedEvent.emit(Unit)
        }
    }

    fun savePerson(person: Person, phoneNumbers: List<PhoneNumber>) {
        viewModelScope.launch {
            peopleRepository.savePerson(person, phoneNumbers)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val currentPwp = peopleRepository.getPersonWithPhones(personId).first()
            if (currentPwp != null) {
                peopleRepository.savePerson(
                    currentPwp.person.copy(isFavorite = !currentPwp.person.isFavorite),
                    currentPwp.phoneNumbers,
                )
            }
        }
    }

    companion object {
        fun factory(app: LatticeApplication, personId: UUID) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PersonDetailViewModel(
                    personId = personId,
                    peopleRepository = app.peopleRepository,
                    journalRepository = app.journalRepository,
                ) as T
        }
    }
}
