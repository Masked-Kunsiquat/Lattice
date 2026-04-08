package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PhoneNumberDao
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/**
 * Aggregate model for UI convenience.
 */
data class PersonWithPhones(
    val person: Person,
    val phoneNumbers: List<PhoneNumber>
)

class PeopleRepository(
    private val personDao: PersonDao,
    private val phoneNumberDao: PhoneNumberDao
) {
    /**
     * Returns a reactive stream of all people with their associated phone numbers.
     * Fulfills Directive 1.2 (Local-First Persistence).
     */
    fun getPeople(): Flow<List<PersonWithPhones>> {
        // We combine the flows to create the aggregate reactive model
        return combine(
            personDao.getPersons(),
            // This is a simple implementation; for very large datasets, 
            // a custom Room @Relation or JOIN would be more performant.
            // But for a personal lattice, this is clean and reactive.
            phoneNumberDao.getAllPhoneNumbers() 
        ) { people, allPhones ->
            people.map { person ->
                PersonWithPhones(
                    person = person,
                    phoneNumbers = allPhones.filter { it.personId == person.id }
                )
            }
        }
    }

    suspend fun savePerson(person: Person, phoneNumbers: List<PhoneNumber>) {
        personDao.insertPerson(person)
        phoneNumberDao.deleteByPersonId(person.id)
        phoneNumbers.forEach { phoneNumberDao.insertPhoneNumber(it) }
    }

    /**
     * Updates the vibe score based on narrative identity logic (Directive 2).
     * This is a placeholder for the evolution algorithm.
     */
    suspend fun updateVibeScore(personId: UUID, delta: Float) {
        personDao.incrementVibeScore(personId, delta)
    }
}
