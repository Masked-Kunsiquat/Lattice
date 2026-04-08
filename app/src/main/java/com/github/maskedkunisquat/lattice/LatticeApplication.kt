package com.github.maskedkunisquat.lattice

import android.app.Application
import androidx.room.Room
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LocalFallbackProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.NanoProvider

class LatticeApplication : Application() {

    val database by lazy {
        Room.databaseBuilder(this, LatticeDatabase::class.java, "lattice.db")
            .addMigrations(LatticeDatabase.MIGRATION_1_2, LatticeDatabase.MIGRATION_2_3)
            .build()
    }

    val embeddingProvider by lazy { EmbeddingProvider() }

    val journalRepository by lazy {
        JournalRepository(
            journalDao = database.journalDao(),
            personDao = database.personDao(),
            embeddingProvider = embeddingProvider,
        )
    }

    val llmOrchestrator by lazy {
        LlmOrchestrator(
            nanoProvider = NanoProvider(this),
            localFallbackProvider = LocalFallbackProvider(this),
            transitEventDao = database.transitEventDao(),
            cloudEnabled = false,
            // cloudProvider omitted: cloud routing is disabled by default.
            // Inject a CloudProvider (+ piiDetector) here when the user enables cloud models.
        )
    }

    override fun onCreate() {
        super.onCreate()
        embeddingProvider.initialize(this)
    }
}
