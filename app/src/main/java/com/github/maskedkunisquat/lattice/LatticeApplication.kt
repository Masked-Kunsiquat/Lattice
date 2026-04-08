package com.github.maskedkunisquat.lattice

import android.app.Application
import androidx.room.Room
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.logic.CloudProvider
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
            cloudProvider = CloudProvider(),
            transitEventDao = database.transitEventDao(),
            cloudEnabled = false,
        )
    }

    override fun onCreate() {
        super.onCreate()
        embeddingProvider.initialize(this)
    }
}
