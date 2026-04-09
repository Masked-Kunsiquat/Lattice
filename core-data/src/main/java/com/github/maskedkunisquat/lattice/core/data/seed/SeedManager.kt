package com.github.maskedkunisquat.lattice.core.data.seed

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Base64
import androidx.room.withTransaction
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters
import com.github.maskedkunisquat.lattice.core.data.model.Mention
import com.github.maskedkunisquat.lattice.core.data.model.MentionSource
import com.github.maskedkunisquat.lattice.core.data.model.MentionStatus
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val RULE_OF_30 = 30
private val PLACEHOLDER_REGEX = Regex("""\[PERSON_([a-fA-F0-9\-]{36})]""")
private val typeConverters = LatticeTypeConverters()

/**
 * Injects and removes persona seed datasets in [LatticeDatabase].
 *
 * Seed files live at `assets/seeds/<persona>.json` (see [SeedPersona.assetFileName]).
 * All operations run in a single Room transaction for atomicity.
 *
 * PII contract: content strings in the JSON must already use `[PERSON_<uuid>]`
 * placeholders. [seedPersona] enforces this by scanning for raw names before any
 * DB writes occur.
 */
private const val PREFS_NAME = "lattice_seed_manager"
private const val EXPECTED_EMBEDDING_BYTES = 384 * Float.SIZE_BYTES // 1536

private data class SeedManifest(
    val entryIds: List<String>,
    val personIds: List<String>,
    val activityIds: List<String>
)

class SeedManager(
    private val db: LatticeDatabase,
    private val context: Context
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    /**
     * Parses and inserts the full dataset for [persona] into the database.
     *
     * @throws IllegalArgumentException if the seed fails validation (Rule of 30,
     *   unresolved placeholders, or raw-name leakage detected in content).
     * @throws IllegalStateException if the database is not at schema version 8.
     */
    suspend fun seedPersona(persona: SeedPersona) = withContext(Dispatchers.IO) {
        check(db.openHelper.readableDatabase.version == 8) {
            "SeedManager requires schema v8 — found v${db.openHelper.readableDatabase.version}"
        }

        val seed = loadSeed(persona)
        validateSeed(seed)

        val manifest = SeedManifest(
            entryIds = seed.journalEntries.map { it.id } + seed.moodLogs.map { it.id },
            personIds = seed.people.map { it.id },
            activityIds = seed.activityHierarchy.map { it.id }
        )

        db.withTransaction {
            // 1. People
            seed.people.forEach { raw ->
                db.personDao().insertPerson(raw.toPerson())
            }

            // 2. Journal entries + mentions + transit events
            seed.journalEntries.forEach { raw ->
                val entryId = UUID.fromString(raw.id)
                db.journalDao().insertEntry(raw.toJournalEntry())
                raw.mentions.forEach { rawMention ->
                    db.mentionDao().insertMention(
                        Mention(
                            entryId = entryId,
                            personId = UUID.fromString(rawMention.personId),
                            source = MentionSource.valueOf(rawMention.source),
                            status = MentionStatus.valueOf(rawMention.status)
                        )
                    )
                }
                db.transitEventDao().insertEvent(
                    TransitEvent(
                        id = UUID.randomUUID(),
                        timestamp = raw.timestamp,
                        providerName = "seed_injection",
                        operationType = "seed",
                        entryId = raw.id
                    )
                )
            }

            // 3. Mood logs (null-content entries) + transit events
            seed.moodLogs.forEach { raw ->
                db.journalDao().insertEntry(raw.toJournalEntry())
                db.transitEventDao().insertEvent(
                    TransitEvent(
                        id = UUID.randomUUID(),
                        timestamp = raw.timestamp,
                        providerName = "seed_injection",
                        operationType = "seed",
                        entryId = raw.id
                    )
                )
            }

            // 4. Activity hierarchy
            seed.activityHierarchy.forEach { raw ->
                db.activityHierarchyDao().insertActivity(
                    ActivityHierarchy(
                        id = UUID.fromString(raw.id),
                        taskName = raw.taskName,
                        difficulty = raw.difficulty,
                        valueCategory = raw.valueCategory
                    )
                )
            }
        }

        persistManifest(persona, manifest)
    }

    /**
     * Removes all entities seeded for [persona] from the database.
     *
     * Uses the [SeedManifest] persisted at seed time (via [persistManifest]) so deletion
     * is not sensitive to changes in the seed file after seeding occurred. Falls back to
     * re-reading the seed file if the manifest is absent (e.g., app reinstall, prefs cleared).
     *
     * Deletion order: transit events → journal entries (cascades mentions) → people
     * (cascades mentions and phone numbers) → activity hierarchy.
     */
    suspend fun clearPersona(persona: SeedPersona) = withContext(Dispatchers.IO) {
        val manifest = loadManifest(persona) ?: run {
            val seed = loadSeed(persona)
            SeedManifest(
                entryIds = seed.journalEntries.map { it.id } + seed.moodLogs.map { it.id },
                personIds = seed.people.map { it.id },
                activityIds = seed.activityHierarchy.map { it.id }
            )
        }

        db.withTransaction {
            // Transit events first — no FK constraint on entryId column
            manifest.entryIds.forEach { db.transitEventDao().deleteEventsForEntry(it) }

            // Journal entries — FK CASCADE removes associated Mentions
            manifest.entryIds.forEach { db.journalDao().deleteEntryById(UUID.fromString(it)) }

            // People — FK CASCADE removes associated Mentions and PhoneNumbers
            manifest.personIds.forEach { db.personDao().deletePersonById(UUID.fromString(it)) }

            manifest.activityIds.forEach {
                db.activityHierarchyDao().deleteActivityById(UUID.fromString(it))
            }
        }

        clearManifest(persona)
    }

    // --- Seed loading ---

    private fun loadSeed(persona: SeedPersona): RawSeed {
        val json = context.assets.open(persona.assetFileName)
            .bufferedReader()
            .use { it.readText() }
        return parseSeed(json)
    }

    // --- Validation ---

    private fun validateSeed(seed: RawSeed) {
        require(seed.journalEntries.size >= RULE_OF_30) {
            "Persona seed has ${seed.journalEntries.size} journal entries — minimum $RULE_OF_30 required for RAG baseline"
        }

        val personIdSet = seed.people.map { it.id }.toSet()

        seed.journalEntries.forEach { entry ->
            // Placeholder resolution: every [PERSON_uuid] must map to a seeded person
            PLACEHOLDER_REGEX.findAll(entry.content).forEach { match ->
                val uuid = match.groupValues[1]
                require(uuid in personIdSet) {
                    "Entry '${entry.id}' references [PERSON_$uuid] which is not in the seed's people list"
                }
            }
            // PII guard: raw person names must not appear in content
            validateNoRawNames(entry.content, seed.people)
        }
    }

    /**
     * Scans [content] for any raw name variant from [people].
     * Variants checked: full name, firstName, lastName, nickname — sorted longest-first
     * to catch "John Smith" before "John".
     */
    private fun validateNoRawNames(content: String, people: List<RawPerson>) {
        val names = people
            .flatMap { p ->
                listOfNotNull(
                    p.lastName?.let { "${p.firstName} $it" },
                    p.firstName,
                    p.lastName,
                    p.nickname
                )
            }
            .sortedByDescending { it.length }

        for (name in names) {
            require(!content.contains(name, ignoreCase = true)) {
                "Seed content contains raw name '$name' — use a [PERSON_<uuid>] placeholder instead"
            }
        }
    }

    // --- JSON parsing ---

    private fun parseSeed(json: String): RawSeed {
        val root = JSONObject(json)
        return RawSeed(
            people = root.getJSONArray("people").parseList { parseRawPerson(it) },
            journalEntries = root.getJSONArray("journalEntries").parseList { parseRawJournalEntry(it) },
            moodLogs = root.optJSONArray("moodLogs")?.parseList { parseRawMoodLog(it) } ?: emptyList(),
            activityHierarchy = root.optJSONArray("activityHierarchy")?.parseList { parseRawActivity(it) } ?: emptyList()
        )
    }

    private fun parseRawPerson(obj: JSONObject) = RawPerson(
        id = obj.getString("id"),
        firstName = obj.getString("firstName"),
        lastName = obj.optString("lastName").takeIf { it.isNotEmpty() },
        nickname = obj.optString("nickname").takeIf { it.isNotEmpty() },
        relationshipType = obj.getString("relationshipType"),
        vibeScore = obj.optDouble("vibeScore", 0.0).toFloat(),
        isFavorite = obj.optBoolean("isFavorite", false)
    )

    private fun parseRawJournalEntry(obj: JSONObject) = RawJournalEntry(
        id = obj.getString("id"),
        timestamp = obj.getLong("timestamp"),
        content = obj.getString("content"),
        valence = obj.getDouble("valence").toFloat(),
        arousal = obj.getDouble("arousal").toFloat(),
        moodLabel = obj.getString("moodLabel"),
        embeddingBase64 = obj.getString("embeddingBase64"),
        cognitiveDistortions = obj.optJSONArray("cognitiveDistortions")
            ?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
        reframedContent = obj.optString("reframedContent").takeIf { it.isNotEmpty() },
        mentions = obj.optJSONArray("mentions")?.parseList { parseRawMention(it) } ?: emptyList()
    )

    private fun parseRawMoodLog(obj: JSONObject) = RawMoodLog(
        id = obj.getString("id"),
        timestamp = obj.getLong("timestamp"),
        valence = obj.getDouble("valence").toFloat(),
        arousal = obj.getDouble("arousal").toFloat(),
        moodLabel = obj.getString("moodLabel")
    )

    private fun parseRawActivity(obj: JSONObject) = RawActivity(
        id = obj.getString("id"),
        taskName = obj.getString("taskName"),
        difficulty = obj.getInt("difficulty"),
        valueCategory = obj.getString("valueCategory")
    )

    private fun parseRawMention(obj: JSONObject) = RawMention(
        personId = obj.getString("personId"),
        source = obj.optString("source", "MANUAL"),
        status = obj.optString("status", "CONFIRMED")
    )

    private fun <T> JSONArray.parseList(transform: (JSONObject) -> T): List<T> =
        List(length()) { i -> transform(getJSONObject(i)) }

    // --- Manifest persistence (SharedPreferences) ---

    private fun persistManifest(persona: SeedPersona, manifest: SeedManifest) {
        val obj = JSONObject().apply {
            put("entryIds", JSONArray(manifest.entryIds))
            put("personIds", JSONArray(manifest.personIds))
            put("activityIds", JSONArray(manifest.activityIds))
        }
        prefs.edit().putString(persona.name, obj.toString()).apply()
    }

    private fun loadManifest(persona: SeedPersona): SeedManifest? {
        val raw = prefs.getString(persona.name, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            fun JSONArray.toStringList() = List(length()) { getString(it) }
            SeedManifest(
                entryIds = obj.getJSONArray("entryIds").toStringList(),
                personIds = obj.getJSONArray("personIds").toStringList(),
                activityIds = obj.getJSONArray("activityIds").toStringList()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun clearManifest(persona: SeedPersona) {
        prefs.edit().remove(persona.name).apply()
    }
}

// --- Entity mapping (file-private, outside class to keep class body lean) ---

private fun RawPerson.toPerson() = Person(
    id = UUID.fromString(id),
    firstName = firstName,
    lastName = lastName,
    nickname = nickname,
    relationshipType = RelationshipType.valueOf(relationshipType),
    vibeScore = vibeScore,
    isFavorite = isFavorite
)

private fun decodeEmbeddingSafely(base64: String): FloatArray {
    return try {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.size != EXPECTED_EMBEDDING_BYTES) return FloatArray(384)
        typeConverters.toFloatArray(bytes) ?: FloatArray(384)
    } catch (_: IllegalArgumentException) {
        FloatArray(384)
    }
}

private fun RawJournalEntry.toJournalEntry() = JournalEntry(
    id = UUID.fromString(id),
    timestamp = timestamp,
    content = content,
    valence = valence,
    arousal = arousal,
    moodLabel = moodLabel,
    embedding = decodeEmbeddingSafely(embeddingBase64),
    cognitiveDistortions = cognitiveDistortions,
    reframedContent = reframedContent
)

private fun RawMoodLog.toJournalEntry() = JournalEntry(
    id = UUID.fromString(id),
    timestamp = timestamp,
    content = null,
    valence = valence,
    arousal = arousal,
    moodLabel = moodLabel,
    embedding = FloatArray(384)
)
