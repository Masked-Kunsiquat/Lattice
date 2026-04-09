package com.github.maskedkunisquat.lattice.core.logic

import com.github.maskedkunisquat.lattice.core.data.dao.JournalDao
import com.github.maskedkunisquat.lattice.core.data.dao.PersonDao
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.sqrt

/**
 * Performs local semantic search over journal entries using cosine similarity.
 *
 * All embeddings in the database were generated from PII-masked text. This repository
 * applies the same masking to the query before vectorizing, ensuring the similarity
 * computation is semantically consistent and no raw PII enters the embedding pipeline.
 */
class SearchRepository(
    private val journalDao: JournalDao,
    private val personDao: PersonDao,
    private val embeddingProvider: EmbeddingProvider
) {
    /**
     * Finds journal entries semantically similar to [queryText], ranked by cosine similarity.
     *
     * Privacy contract:
     * 1. [queryText] is masked via [PiiShield] before being embedded — raw PII never reaches
     *    the embedding pipeline.
     * 2. Returned entries have their content unmasked for UI display.
     *
     * @param queryText Free-text search query (may contain real names / PII).
     * @param limit Maximum number of results to return.
     * @return Flow emitting a ranked list of the [limit] most similar entries.
     */
    fun findSimilarEntries(queryText: String, limit: Int): Flow<List<JournalEntry>> = flow {
        val people = personDao.getPersons().first()

        // Privacy: mask PII in the query so it aligns with masked stored embeddings
        val maskedQuery = PiiShield.mask(queryText, people)
        val queryEmbedding = embeddingProvider.generateEmbedding(maskedQuery)

        val results = journalDao.getAllEntries()
            .map { entry -> entry to cosineSimilarity(queryEmbedding, entry.embedding) }
            .filter { (_, score) -> score.isFinite() && score > 0f }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (entry, _) -> entry.copy(content = entry.content?.let { PiiShield.unmask(it, people) }) }

        emit(results)
    }.flowOn(Dispatchers.Default)

    /**
     * Finds past journal entries that serve as "Evidence for the Contrary" — positive
     * experiences involving the same entities as the current entry.
     *
     * Entries are filtered to:
     * 1. `valence > [minValence]` — positive quadrant only.
     * 2. `maskedContent` contains at least one placeholder from [placeholders] — anchors
     *    the evidence to the same people/entities referenced in the current entry.
     *
     * Results are ordered by valence descending (most positive first) and capped at [limit].
     * Content is returned masked (callers receive the stored masked form; this is intentional
     * since evidence is injected into LLM prompts which operate on masked text).
     *
     * @param placeholders Set of `[PERSON_UUID]` tokens extracted from the current entry's
     *   masked text. Used for cross-entry entity anchoring.
     * @param minValence Minimum valence threshold (exclusive). Defaults to 0.5.
     * @param limit Maximum number of evidence entries to return. Defaults to 5.
     */
    fun findEvidenceEntries(
        placeholders: Set<String>,
        minValence: Float = 0.5f,
        limit: Int = 5,
    ): Flow<List<JournalEntry>> = flow {
        val candidates = journalDao.getEntriesWithMinValence(minValence)
        val results = candidates
            .filter { entry -> val c = entry.content; c != null && (placeholders.isEmpty() || placeholders.any { it in c }) }
            .take(limit)
        emit(results)
    }.flowOn(Dispatchers.Default)

    /**
     * Computes the cosine similarity between two 384-dimensional float vectors.
     * Returns 0f when either vector is the zero-vector (no valid direction).
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat()
    }
}
