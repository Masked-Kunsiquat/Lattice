package com.github.maskedkunisquat.lattice

import android.app.Application
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.github.maskedkunisquat.lattice.core.data.KeyProvider
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
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.io.File

private val Application.settingsDataStore by preferencesDataStore(name = "lattice_settings")

private const val TAG = "LatticeApplication"
private const val PREF_ENCRYPTION_DONE = "encryption_migration_done"

class LatticeApplication : Application() {

    val database by lazy {
        val passphrase = KeyProvider.getOrCreateKey(this)
        Room.databaseBuilder(this, LatticeDatabase::class.java, "lattice.db")
            .openHelperFactory(SupportFactory(passphrase))
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
            cloudEnabled = { settingsRepository.settings.first().cloudEnabled },
        )
    }

    override fun onCreate() {
        super.onCreate()

        // Must be called before any SQLCipher operation.
        SQLiteDatabase.loadLibs(this)

        // One-time migration: re-encrypt any existing plaintext DB on upgrade.
        migrateToEncryptedDbIfNeeded()

        embeddingProvider.initialize(this)
        Thread { localFallbackProvider.initialize() }.start()
    }

    /**
     * On the first launch after SQLCipher is added, the on-disk DB is still plaintext.
     * This function detects that case and exports the data into a new encrypted file,
     * then replaces the original. Guarded by a plain SharedPreferences flag so it only
     * runs once and completes synchronously before Room opens the DB.
     */
    private fun migrateToEncryptedDbIfNeeded() {
        val prefs = getSharedPreferences("lattice_migration", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ENCRYPTION_DONE, false)) return

        val dbFile = getDatabasePath("lattice.db")
        if (!dbFile.exists()) {
            // Fresh install — no plaintext DB to migrate.
            prefs.edit().putBoolean(PREF_ENCRYPTION_DONE, true).apply()
            return
        }

        // Attempt to open the existing file as plaintext SQLCipher (empty passphrase).
        // If it's already encrypted this will fail — which is fine, we skip the migration.
        val tempFile = File(dbFile.parent, "lattice_encrypted.db")
        try {
            val keyBytes = KeyProvider.getOrCreateKey(this)
            val hex = keyBytes.joinToString("") { "%02x".format(it) }

            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                ByteArray(0),   // empty passphrase = open as plaintext
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY \"x'$hex'\";")
            db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            db.rawExecSQL("DETACH DATABASE encrypted;")
            db.close()

            // Replace plaintext original with the encrypted copy.
            dbFile.delete()
            tempFile.renameTo(dbFile)
            Log.i(TAG, "Encrypted DB migration complete.")
        } catch (e: Exception) {
            // Either already encrypted, or migration failed. Either way, clean up temp
            // and let Room open the file — if it's already encrypted it will work fine.
            tempFile.delete()
            Log.w(TAG, "Encrypted DB migration skipped or failed (may already be encrypted)", e)
        } finally {
            prefs.edit().putBoolean(PREF_ENCRYPTION_DONE, true).apply()
        }
    }
}
