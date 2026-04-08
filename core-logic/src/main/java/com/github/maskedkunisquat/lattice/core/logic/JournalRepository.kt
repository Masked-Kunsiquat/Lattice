package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.UUID

class JournalRepository(
    private val journalDao: JournalDao,
    private val personDao: PersonDao,
    private val embeddingProvider: EmbeddingProvider
) {
    /**
     * Returns a flow of journal entries with content unmasked for UI display.
     * This fulfills the Prime Directive: Local-First Persistence + PII Isolation.
     */
    fun getEntries(): Flow<List<JournalEntry>> {
        return combine(
            journalDao.getEntries(),
            personDao.getPersons()
        ) { entries, people ->
            entries.map { it.copy(content = PiiShield.unmask(it.content, people)) }
        }
    }

    /**
     * Returns a single journal entry by ID, unmasked.
     */
    fun getEntryById(id: UUID): Flow<JournalEntry?> {
        return combine(
            journalDao.getEntryById(id),
            personDao.getPersons()
        ) { entry, people ->
            entry?.copy(content = PiiShield.unmask(entry.content, people))
        }
    }

    /**
     * Saves a journal entry, ensuring PII is masked before hitting the database.
     * This enforces the PII Isolation Prime Directive.
     * Also updates the vibeScore of mentioned persons based on entry valence.
     */
    suspend fun saveEntry(entry: JournalEntry) {
        val people = personDao.getPersons().first()
        
        // Ensure the mood label matches the scientific model
        val calculatedLabel = CircumplexMapper.getLabel(entry.valence, entry.arousal).name
        
        val maskedContent = PiiShield.mask(entry.content, people)
        val distortions = CbtLogic.detectDistortions(maskedContent)
        val embedding = embeddingProvider.generateEmbedding(maskedContent)

        val entryToSave = entry.copy(
            content = maskedContent,
            moodLabel = calculatedLabel,
            cognitiveDistortions = distortions,
            embedding = embedding
        )

        journalDao.insertEntry(entryToSave)

        // Vibe Evolution: Update vibe scores for mentioned persons
        // Extract UUIDs from [PERSON_UUID] placeholders
        val regex = Regex("\\[PERSON_([a-fA-F0-9-]{36})\\]")
        val mentions = regex.findAll(maskedContent)
            .map { it.groupValues[1] }
            .distinct()
            .map { UUID.fromString(it) }

        val delta = entry.valence * 0.1f
        mentions.forEach { personId ->
            personDao.incrementVibeScore(personId, delta)
        }
    }

    suspend fun deleteEntry(entry: JournalEntry) {
        journalDao.deleteEntry(entry)
    }

    /**
     * Returns [text] with all known people's names replaced by [PERSON_uuid] placeholders.
     * Used by callers that need masked text without persisting an entry (e.g. the reframe pipeline).
     */
    suspend fun maskText(text: String): String {
        val people = personDao.getPersons().first()
        return PiiShield.mask(text, people)
    }
}
