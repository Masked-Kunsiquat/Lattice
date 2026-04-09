package com.github.maskedkunisquat.lattice.core.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Manages the SQLCipher database encryption key.
 *
 * The key is a 32-byte (256-bit) random value generated once on first launch.
 * It is stored in [EncryptedSharedPreferences] backed by an AES-256-GCM key
 * in the Android Keystore (hardware-backed on devices with StrongBox or TEE).
 *
 * ## Threat model
 * - Stolen/rooted device: the DB file is unreadable without the Keystore-protected key.
 * - Key material never appears in plaintext outside the Keystore boundary.
 * - Key survives app restarts (written once to EncryptedSharedPreferences).
 */
object KeyProvider {

    private const val PREF_FILE = "lattice_db_prefs"
    private const val KEY_ALIAS = "lattice_db_key"

    /**
     * Returns the existing DB encryption key, or generates and persists a new one.
     *
     * Thread-safe: [EncryptedSharedPreferences] operations are internally synchronised.
     * Safe to call from any thread; typically called once in [Application.onCreate].
     */
    fun getOrCreateKey(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            PREF_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        return prefs.getString(KEY_ALIAS, null)
            ?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: ByteArray(32).also { key ->
                SecureRandom().nextBytes(key)
                val committed = prefs.edit()
                    .putString(KEY_ALIAS, Base64.encodeToString(key, Base64.DEFAULT))
                    .commit()
                if (!committed) {
                    throw IllegalStateException("KeyProvider: failed to persist DB encryption key to EncryptedSharedPreferences")
                }
            }
    }
}
