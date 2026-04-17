package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.MentionDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.PlaceDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

class JournalRepository(
    private val journalDao: JournalDao,
    private val personDao: PersonDao,
    private val mentionDao: MentionDao,
    private val transitEventDao: TransitEventDao,
    private val embeddingProvider: EmbeddingProvider,
    private val placeDao: PlaceDao,
) {
    /**
     * Returns a flow of journal entries with content unmasked for UI display.
     * This fulfills the Prime Directive: Local-First Persistence + PII Isolation.
     */
    fun getEntries(): Flow<List<JournalEntry>> {
        return combine(
            journalDao.getEntries(),
            personDao.getPersons(),
            placeDao.getAll(),
        ) { entries, people, places ->
            entries.map { it.copy(content = it.content?.let { c -> PiiShield.unmask(c, people, places) }) }
        }
    }

    /**
     * Returns a flow of journal entries for a specific person, unmasked.
     */
    fun getEntriesForPerson(personId: UUID): Flow<List<JournalEntry>> {
        return combine(
            journalDao.getEntriesForPerson(personId),
            personDao.getPersons(),
            placeDao.getAll(),
        ) { entries, people, places ->
            entries.map { it.copy(content = it.content?.let { c -> PiiShield.unmask(c, people, places) }) }
        }
    }

    /**
     * Returns a single journal entry by ID, unmasked.
     */
    fun getEntryById(id: UUID): Flow<JournalEntry?> {
        return combine(
            journalDao.getEntryById(id),
            personDao.getPersons(),
            placeDao.getAll(),
        ) { entry, people, places ->
            entry?.copy(
                content         = entry.content?.let { PiiShield.unmask(it, people, places) },
                reframedContent = entry.reframedContent?.let { PiiShield.unmask(it, people, places) },
            )
        }
    }

    /**
     * Returns an entry loaded for the editor: `[PERSON_uuid]` placeholders are replaced
     * with `@displayName` (and `[PLACE_uuid]` with `!displayName`) so the editor text
     * reflects the mention-syntax the user typed. Also returns the resolved maps needed
     * by [PiiHighlightTransformation] to colour the mentions.
     */
    fun getEntryForEditor(id: UUID): Flow<Triple<JournalEntry?, Map<String, UUID>, Map<String, UUID>>> {
        return combine(
            journalDao.getEntryById(id),
            personDao.getPersons(),
            placeDao.getAll(),
        ) { entry, people, places ->
            val raw: String = entry?.content
                ?: return@combine Triple(entry, emptyMap<String, UUID>(), emptyMap<String, UUID>())

            val personById = people.associateBy { it.id }
            val placeById  = places.associateBy { it.id }
            val resolvedPersons = mutableMapOf<String, UUID>()
            val resolvedPlaces  = mutableMapOf<String, UUID>()

            PERSON_PLACEHOLDER.findAll(raw).forEach { m ->
                val uuid = UUID.fromString(m.groupValues[1])
                personById[uuid]?.let { p ->
                    resolvedPersons[p.nickname ?: p.firstName] = uuid
                }
            }
            PLACE_PLACEHOLDER.findAll(raw).forEach { m ->
                val uuid = UUID.fromString(m.groupValues[1])
                placeById[uuid]?.let { pl -> resolvedPlaces[pl.name] = uuid }
            }

            var text = raw
            // Longest names first so "John Smith" isn't split by "John"
            resolvedPersons.entries.sortedByDescending { it.key.length }
                .forEach { (name, uuid) -> text = text.replace("[PERSON_$uuid]", "@$name") }
            resolvedPlaces.entries.sortedByDescending { it.key.length }
                .forEach { (name, uuid) -> text = text.replace("[PLACE_$uuid]", "!$name") }

            Triple(entry.copy(content = text), resolvedPersons.toMap(), resolvedPlaces.toMap())
        }
    }

    companion object {
        private val PERSON_PLACEHOLDER = Regex("""\[PERSON_([a-fA-F0-9\-]{36})]""")
        private val PLACE_PLACEHOLDER  = Regex("""\[PLACE_([a-fA-F0-9\-]{36})]""")
    }

    /**
     * Saves a journal entry, ensuring PII is masked before hitting the database.
     * This enforces the PII Isolation Prime Directive.
     * Also updates the vibeScore of mentioned persons based on entry valence.
     */
    suspend fun saveEntry(entry: JournalEntry) {
        val people = personDao.getPersons().first()
        val places = placeDao.getAll().first()

        // Ensure the mood label matches the scientific model
        val calculatedLabel = CircumplexMapper.getLabel(entry.valence, entry.arousal).name

        val maskedContent = entry.content?.let { PiiShield.mask(it, people, places) }
        val distortions = if (maskedContent != null) CbtLogic.detectDistortions(maskedContent) else emptyList()
        val embedding = if (maskedContent != null) embeddingProvider.generateEmbedding(maskedContent) else FloatArray(384)

        val entryToSave = entry.copy(
            content = maskedContent,
            moodLabel = calculatedLabel,
            cognitiveDistortions = distortions,
            embedding = embedding
        )

        journalDao.insertEntry(entryToSave)

        // Vibe Evolution: Update vibe scores for mentioned persons
        // Extract UUIDs from [PERSON_UUID] placeholders
        val mentions = maskedContent?.let { PERSON_PLACEHOLDER.findAll(it) }
            ?.map { it.groupValues[1] }
            ?.distinct()
            ?.map { UUID.fromString(it) }
            ?: emptySequence()

        val delta = entry.valence * 0.1f
        mentions.forEach { personId ->
            personDao.incrementVibeScore(personId, delta)
        }
    }

    suspend fun deleteEntry(entry: JournalEntry) = withContext(Dispatchers.IO) {
        // 1. Reverse vibe score increments for all mentioned persons
        val mentions = mentionDao.getMentionsByEntry(entry.id)
        val delta = -(entry.valence * 0.1f)
        mentions.forEach { personDao.incrementVibeScore(it.personId, delta) }
        // 2. Delete entry — CASCADE removes Mentions via FK
        journalDao.deleteEntry(entry)
        // 3. Prune orphaned TransitEvents
        transitEventDao.deleteEventsForEntry(entry.id.toString())
    }

    /**
     * Persists the CBT reframe text the user accepted via the "Apply" action.
     * Writes to the [reframedContent] column of the entry identified by [entryId].
     * No additional [TransitEvent] is logged here — one was already written by
     * the reframe pipeline at generation time.
     */
    suspend fun updateReframedContent(entryId: String, content: String) {
        journalDao.updateReframedContent(entryId, content)
    }

    /**
     * Persists field changes to an existing entry, re-masking [JournalEntry.content] and
     * [JournalEntry.reframedContent] before writing. Does not regenerate the embedding,
     * distortions, or mood label — use [saveEntry] for new entries.
     */
    suspend fun updateEntry(entry: JournalEntry) {
        val people = personDao.getPersons().first()
        val places = placeDao.getAll().first()
        journalDao.updateEntry(entry.copy(
            content = entry.content?.let { PiiShield.mask(it, people, places) },
            reframedContent = entry.reframedContent?.let { PiiShield.mask(it, people, places) },
        ))
    }

    /**
     * Returns [text] with all known people's names replaced by [PERSON_uuid] placeholders.
     * Used by callers that need masked text without persisting an entry (e.g. the reframe pipeline).
     */
    suspend fun maskText(text: String): String {
        val people = personDao.getPersons().first()
        val places = placeDao.getAll().first()
        return PiiShield.mask(text, people, places)
    }
}
