package com.github.maskedkunisquat.lattice.core.logic

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.data.seed.SeedManager
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var db: LatticeDatabase
    private lateinit var searchRepository: SearchRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LatticeDatabase::class.java).build()

        // Embedding provider stays warm for the duration of the benchmark — we are
        // measuring search cost (cosine scan + PII masking), not model load cost.
        val embeddingProvider = EmbeddingProvider()
        embeddingProvider.initialize(context)

        // Seed all 3 personas: Holmes (35) + Watson (30) + Werther (30) = 95 entries.
        val seedManager = SeedManager(db, context)
        SeedPersona.entries.forEach { seedManager.seedPersona(it) }

        searchRepository = SearchRepository(db.journalDao(), db.personDao(), embeddingProvider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Measures the full [SearchRepository.findSimilarEntries] pipeline across 100 calls:
     * PiiShield.mask → EmbeddingProvider.generateEmbedding → O(n) cosine scan over all 95
     * entries → sort → take(5).
     */
    @Test
    fun findSimilarEntries_100calls() {
        // Masked text — no raw PII enters the query path.
        val query = "I feel completely overwhelmed and cannot focus on anything today."
        benchmarkRule.measureRepeated {
            repeat(100) {
                runBlocking { searchRepository.findSimilarEntries(query, limit = 5) }
            }
        }
    }

    /**
     * Measures [SearchRepository.findEvidenceEntries] across 100 calls.
     * Uses Watson's person placeholder so the filter has real matches to evaluate
     * (entries referencing [PERSON_a1a1...] with valence > 0.5).
     */
    @Test
    fun findEvidenceEntries_100calls() {
        val placeholders = setOf("[PERSON_a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1]")
        benchmarkRule.measureRepeated {
            repeat(100) {
                runBlocking { searchRepository.findEvidenceEntries(placeholders) }
            }
        }
    }
}
