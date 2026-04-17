package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.core.logic.PeopleRepository
import com.github.maskedkunisquat.lattice.core.logic.PersonWithPhones
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

data class PeopleListUiState(
    val people: List<PersonWithPhones> = emptyList(),
)

class PeopleListViewModel(
    private val peopleRepository: PeopleRepository,
) : ViewModel() {

    val uiState: StateFlow<PeopleListUiState> = peopleRepository.getPeople()
        .map { people ->
            val sorted = people.sortedWith(
                compareByDescending<PersonWithPhones> { it.person.isFavorite }
                    .thenByDescending { abs(it.person.vibeScore) }
            )
            PeopleListUiState(people = sorted)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleListUiState())

    fun addPerson(
        firstName: String,
        lastName: String?,
        nickname: String?,
        relationshipType: RelationshipType,
    ) {
        viewModelScope.launch {
            val person = Person(
                id = UUID.randomUUID(),
                firstName = firstName.trim(),
                lastName = lastName?.trim()?.ifBlank { null },
                nickname = nickname?.trim()?.ifBlank { null },
                relationshipType = relationshipType,
            )
            peopleRepository.insertPerson(person)
        }
    }

    fun deletePerson(personId: UUID) {
        viewModelScope.launch {
            peopleRepository.deletePerson(personId)
        }
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PeopleListViewModel(peopleRepository = app.peopleRepository) as T
        }
    }
}
