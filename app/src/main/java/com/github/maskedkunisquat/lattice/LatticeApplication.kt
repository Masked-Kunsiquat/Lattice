package com.github.maskedkunisquat.lattice

import android.app.Application
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LocalFallbackProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.NanoProvider
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import com.github.maskedkunisquat.lattice.core.logic.SearchRepository
import com.github.maskedkunisquat.lattice.core.logic.SettingsRepository
import kotlinx.coroutines.flow.first

private val Application.settingsDataStore by preferencesDataStore(name = "lattice_settings")

class LatticeApplication : Application() {

    val database by lazy {
        Room.databaseBuilder(this, LatticeDatabase::class.java, "lattice.db")
            .addMigrations(
                LatticeDatabase.MIGRATION_1_2,
                LatticeDatabase.MIGRATION_2_3,
                LatticeDatabase.MIGRATION_3_4,
                LatticeDatabase.MIGRATION_4_5,
                LatticeDatabase.MIGRATION_5_6,
            )
            .build()
    }

    val settingsRepository by lazy { SettingsRepository(settingsDataStore) }

    val embeddingProvider by lazy { EmbeddingProvider() }

    val localFallbackProvider by lazy { LocalFallbackProvider(this) }

    val journalRepository by lazy {
        JournalRepository(
            journalDao = database.journalDao(),
            personDao = database.personDao(),
            mentionDao = database.mentionDao(),
            transitEventDao = database.transitEventDao(),
            embeddingProvider = embeddingProvider,
        )
    }

    val searchRepository by lazy {
        SearchRepository(
            journalDao = database.journalDao(),
            personDao = database.personDao(),
            embeddingProvider = embeddingProvider,
        )
    }

    val reframingLoop by lazy {
        ReframingLoop(llmOrchestrator, database.activityHierarchyDao(), searchRepository)
    }

    val llmOrchestrator by lazy {
        LlmOrchestrator(
            nanoProvider = NanoProvider(this),
            localFallbackProvider = localFallbackProvider,
            transitEventDao = database.transitEventDao(),
            // cloudEnabled reads live from DataStore — survives process restarts and
            // updates immediately when the user toggles the setting in SettingsScreen.
            cloudEnabled = { settingsRepository.settings.first().cloudEnabled },
            // cloudProvider / piiDetector omitted until cloud routing is user-enabled.
        )
    }

    override fun onCreate() {
        super.onCreate()
        embeddingProvider.initialize(this)
        // Kick off the one-time asset→filesDir copy + ONNX session open in the background.
        // isAvailable() lazily triggers this too, but doing it eagerly avoids a multi-second
        // stall on the first reframe request after install/update.
        Thread { localFallbackProvider.initialize() }.start()
    }
}
