package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PhoneNumberDao
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val phoneNumberDao: PhoneNumberDao,
    /**
     * Executes [block] inside an atomic transaction. Callers provide this at construction
     * time, typically as `{ database.withTransaction(it) }`. Unit tests can pass `{ it() }`.
     */
    private val transact: suspend (block: suspend () -> Unit) -> Unit,
) {
    /**
     * Returns a reactive stream for a single person with their phone numbers.
     * Emits null when the person does not exist (e.g., after deletion).
     */
    fun getPersonWithPhones(personId: UUID): Flow<PersonWithPhones?> =
        combine(
            personDao.getPersonById(personId),
            phoneNumberDao.getPhoneNumbersForPerson(personId),
        ) { person, phones ->
            person?.let { PersonWithPhones(it, phones) }
        }

    fun getPeople(): Flow<List<PersonWithPhones>> {
        return combine(
            personDao.getPersons(),
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
        transact {
            personDao.insertPerson(person)
            phoneNumberDao.deleteByPersonId(person.id)
            phoneNumberDao.insertPhoneNumbers(phoneNumbers)
        }
    }

    /**
     * Updates the vibe score based on narrative identity logic (Directive 2).
     * This is a placeholder for the evolution algorithm.
     */
    suspend fun updateVibeScore(personId: UUID, delta: Float) {
        personDao.incrementVibeScore(personId, delta)
    }

    suspend fun searchByName(query: String): List<Person> =
        personDao.searchByName(query).first()

    suspend fun insertPerson(person: Person) {
        personDao.insertPerson(person)
    }

    suspend fun deletePerson(personId: UUID) {
        personDao.deletePersonById(personId)
    }
}
