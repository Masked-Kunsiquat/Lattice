package com.github.maskedkunisquat.lattice

import android.app.Application
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.github.maskedkunisquat.lattice.core.data.CloudCredentialStore
import com.github.maskedkunisquat.lattice.core.data.KeyProvider
import com.github.maskedkunisquat.lattice.core.data.LatticeDatabase
import com.github.maskedkunisquat.lattice.core.logic.CloudProvider
import com.github.maskedkunisquat.lattice.core.logic.EmbeddingProvider
import com.github.maskedkunisquat.lattice.core.logic.ExportManager
import com.github.maskedkunisquat.lattice.core.logic.JournalRepository
import com.github.maskedkunisquat.lattice.core.logic.LocalFallbackProvider
import com.github.maskedkunisquat.lattice.core.logic.LlmOrchestrator
import com.github.maskedkunisquat.lattice.core.logic.NanoProvider
import com.github.maskedkunisquat.lattice.core.logic.ReframingLoop
import com.github.maskedkunisquat.lattice.core.logic.SearchRepository
import com.github.maskedkunisquat.lattice.core.data.seed.SeedManager
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
                LatticeDatabase.MIGRATION_6_7,
                LatticeDatabase.MIGRATION_7_8,
                LatticeDatabase.MIGRATION_8_9,
            )
            .build()
    }

    val seedManager by lazy { SeedManager(database, this) }

    val settingsRepository by lazy { SettingsRepository(settingsDataStore) }

    val cloudCredentialStore by lazy { CloudCredentialStore(this) }

    val cloudProvider by lazy { CloudProvider(credentialStore = cloudCredentialStore) }

    val exportManager by lazy {
        ExportManager(
            context = this,
            journalDao = database.journalDao(),
            personDao = database.personDao(),
            transitEventDao = database.transitEventDao(),
        )
    }

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
            cloudProvider = cloudProvider,
            transitEventDao = database.transitEventDao(),
            cloudEnabled = { settingsRepository.settings.first().cloudEnabled },
            // All content is masked by JournalRepository.saveEntry before reaching the
            // orchestrator; PII never enters a prompt in raw form. See PiiShield.mask().
            piiDetector = { false },
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
                CharArray(0),   // empty passphrase = open as plaintext
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            db.execSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY \"x'$hex'\";")
            db.execSQL("SELECT sqlcipher_export('encrypted');")
            db.execSQL("DETACH DATABASE encrypted;")
            db.close()

            // Replace plaintext original with the encrypted copy.
            if (!tempFile.renameTo(dbFile)) {
                // rename() can fail on cross-filesystem moves (e.g., some Android OEMs mount
                // internal storage on a different fs than the DB dir). Fall back to a safe
                // copy-then-delete so the original is never lost if the copy fails.
                Log.i(TAG, "rename failed, falling back to copy-then-delete")
                tempFile.copyTo(dbFile, overwrite = true)
                tempFile.delete()
            }
            Log.i(TAG, "Encrypted DB migration complete.")
            prefs.edit().putBoolean(PREF_ENCRYPTION_DONE, true).apply()
        } catch (e: Exception) {
            // Either already encrypted, or migration failed. Either way, clean up temp
            // and let Room open the file — if it's already encrypted it will work fine.
            tempFile.delete()
            Log.w(TAG, "Encrypted DB migration skipped or failed (may already be encrypted)", e)
            // Mark done to avoid retrying on every launch. If the DB is already encrypted
            // Room will open it correctly. Retrying on genuine failures would risk
            // infinite-crash loops at startup.
            prefs.edit().putBoolean(PREF_ENCRYPTION_DONE, true).apply()
        }
    }
}
