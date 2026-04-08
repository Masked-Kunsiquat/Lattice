package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.dao.TransitEventDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Produces a portable `manifest.json` that maps every local Room entity to a
 * human-readable JSON representation — the "Lattice Standard" export format.
 *
 * Key design goals:
 * - **Portability:** Every export includes [MODEL_SPEC] so future apps know which
 *   embedding model was used and can re-interpret or re-compute embeddings.
 * - **Human-readable:** Field names are descriptive; enums are lowercase strings.
 * - **Privacy-preserving:** Journal content is exported in its MASKED form
 *   (`content_masked`). Raw embeddings are excluded (large, not human-readable).
 * - **Audit trail:** Transit events are included so users can see when data left
 *   the device.
 *
 * See `SPEC.md` in the project root for the full schema definition.
 */
class ExportManager(
    private val journalDao: JournalDao,
    private val personDao: PersonDao,
    private val transitEventDao: TransitEventDao
) {
    /**
     * Generates the full export manifest as a pretty-printed JSON string.
     *
     * Runs on [Dispatchers.IO] — safe to call from any coroutine.
     */
    suspend fun generateManifest(): String = withContext(Dispatchers.IO) {
        val entries = journalDao.getAllEntries()
        val people = personDao.getPersons().first()
        val transitEvents = transitEventDao.getAllEvents()

        val manifest = JSONObject().apply {
            put("export_version", MANIFEST_VERSION)
            put("exported_at", isoTimestamp())
            put("schema_version", SCHEMA_VERSION)
            put("model_spec", buildModelSpec())
            put("data", JSONObject().apply {
                put("people", JSONArray().also { arr -> people.forEach { arr.put(it.toJson()) } })
                put("journal_entries", JSONArray().also { arr -> entries.forEach { arr.put(it.toJson()) } })
                put("transit_events", JSONArray().also { arr -> transitEvents.forEach { arr.put(it.toJson()) } })
            })
        }

        manifest.toString(2)
    }

    private fun buildModelSpec() = JSONObject().apply {
        put("embedding_model", "snowflake-arctic-embed-xs")
        put("embedding_dimensions", EmbeddingProvider.EMBEDDING_DIM)
        put("embedding_type", "float32")
        put("embedding_pooling", "mean")
        put("model_source", "https://huggingface.co/Snowflake/snowflake-arctic-embed-xs")
        put("note", "Embeddings are stored as comma-separated float32 values in Room. " +
                    "Re-embed content_masked with this model to restore semantic search.")
    }

    private fun Person.toJson() = JSONObject().apply {
        put("id", id.toString())
        put("first_name", firstName)
        putOpt("last_name", lastName)
        putOpt("nickname", nickname)
        put("relationship_type", relationshipType.name.lowercase(Locale.ROOT))
        put("vibe_score", vibeScore.toDouble())
        put("is_favorite", isFavorite)
    }

    private fun JournalEntry.toJson() = JSONObject().apply {
        put("id", id.toString())
        put("timestamp_ms", timestamp)
        put("timestamp_human", isoTimestamp(timestamp))
        // Content is exported in its masked form — [PERSON_uuid] placeholders are intentional.
        // Cross-reference the "people" array to resolve them.
        put("content_masked", content)
        put("valence", valence.toDouble())
        put("arousal", arousal.toDouble())
        put("mood_label", moodLabel.lowercase(Locale.ROOT))
        put("cognitive_distortions", JSONArray(cognitiveDistortions))
        // Raw embedding vector intentionally excluded — too large and not human-readable.
        // Use model_spec to re-embed content_masked if needed.
    }

    private fun TransitEvent.toJson() = JSONObject().apply {
        put("id", id.toString())
        put("timestamp_ms", timestamp)
        put("timestamp_human", isoTimestamp(timestamp))
        put("provider", providerName)
        put("operation", operationType)
    }

    private fun isoTimestamp(epochMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))

    companion object {
        const val MANIFEST_VERSION = "1.0"
        const val SCHEMA_VERSION = "lattice-v2"
    }
}
