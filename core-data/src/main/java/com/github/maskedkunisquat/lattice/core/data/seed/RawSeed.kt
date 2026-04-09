package com.github.maskedkunisquat.lattice.core.data.seed

/**
 * Root container for a persona seed file (assets/seeds/<persona>.json).
 *
 * Distribution contract ("Lattice Mix"):
 *   - 70% [journalEntries]
 *   - 20% [moodLogs]
 *   - 10% [people] + [activityHierarchy]
 *
 * Minimum 30 [journalEntries] per persona required for a viable RAG baseline.
 */
data class RawSeed(
    val people: List<RawPerson>,
    val journalEntries: List<RawJournalEntry>,
    val moodLogs: List<RawMoodLog>,
    val activityHierarchy: List<RawActivity> = emptyList()
)

/**
 * Raw person record. Maps directly to the [com.github.maskedkunisquat.lattice.core.data.model.Person]
 * entity. Raw names live here and nowhere else — all journal/mood content must use
 * [PERSON_<id>] placeholders instead.
 *
 * @param relationshipType Must match a [com.github.maskedkunisquat.lattice.core.data.model.RelationshipType]
 *   enum name: FAMILY, FRIEND, PROFESSIONAL, or ACQUAINTANCE.
 */
data class RawPerson(
    val id: String,
    val firstName: String,
    val lastName: String? = null,
    val nickname: String? = null,
    val relationshipType: String,
    val vibeScore: Float = 0f,
    val isFavorite: Boolean = false
)

/**
 * Raw journal entry. Content must be pre-masked: all person references replaced with
 * [PERSON_<uuid>] placeholders matching UUIDs in the same seed file's [RawSeed.people] list.
 *
 * @param embeddingBase64 Base64-encoded IEEE 754 float32 little-endian byte array produced by
 *   snowflake-arctic-embed-xs (384 floats = 1536 bytes). This byte layout matches
 *   [com.github.maskedkunisquat.lattice.core.data.model.LatticeTypeConverters.fromFloatArray]
 *   exactly — SeedManager decodes via Base64.getDecoder().decode() and stores the raw bytes.
 *   Use a zero-filled placeholder ("AAAA...") for entries that will be re-embedded at seed time.
 * @param mentions Person UUIDs referenced by [PERSON_<uuid>] placeholders in [content].
 *   SeedManager inserts a [com.github.maskedkunisquat.lattice.core.data.model.Mention] row
 *   for each entry in this list.
 * @param cognitiveDistortions Distortion labels, e.g. "EMOTIONAL_REASONING", "CATASTROPHIZING".
 */
data class RawJournalEntry(
    val id: String,
    val timestamp: Long,
    val content: String,
    val valence: Float,
    val arousal: Float,
    val moodLabel: String,
    val embeddingBase64: String,
    val cognitiveDistortions: List<String> = emptyList(),
    val reframedContent: String? = null,
    val mentions: List<RawMention> = emptyList()
)

/**
 * Mood-only log entry — valid (valence, arousal) coordinates with no text content.
 *
 * NOTE: [com.github.maskedkunisquat.lattice.core.data.model.JournalEntry.content] is currently
 * non-nullable (schema v7). SeedManager maps these to JournalEntry using an empty string
 * sentinel until the v8 migration decision (Option A: nullable content) is resolved.
 */
data class RawMoodLog(
    val id: String,
    val timestamp: Long,
    val valence: Float,
    val arousal: Float,
    val moodLabel: String
)

/**
 * Raw activity for the BA hierarchy. Maps to
 * [com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy].
 *
 * @param difficulty Subjective rating 0–10.
 * @param valueCategory e.g. "connection", "achievement", "vitality".
 */
data class RawActivity(
    val id: String,
    val taskName: String,
    val difficulty: Int,
    val valueCategory: String
)

/**
 * A mention link inside a [RawJournalEntry]. [personId] must resolve to a [RawPerson.id]
 * in the same seed file.
 *
 * @param source MANUAL or AUTO.
 * @param status CONFIRMED or PENDING.
 */
data class RawMention(
    val personId: String,
    val source: String = "MANUAL",
    val status: String = "CONFIRMED"
)
