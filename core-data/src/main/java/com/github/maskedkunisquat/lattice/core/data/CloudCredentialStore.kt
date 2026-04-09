package com.github.maskedkunisquat.lattice.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for cloud provider API keys.
 *
 * Keys are stored in [EncryptedSharedPreferences] backed by an AES-256-GCM key in the
 * Android Keystore. Key material never appears in plaintext in SharedPreferences, logcat,
 * or exported data.
 *
 * ## Threat model
 * - On-disk preferences file is encrypted; readable only with the Keystore-backed master key.
 * - Hardware-backed on devices with StrongBox or TEE.
 * - No key is ever written to the export manifest ([ExportManager] excludes this store).
 */
class CloudCredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cloud_credentials",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Stores [key] for [provider]. Overwrites any existing key. */
    fun setApiKey(provider: String, key: String) {
        prefs.edit().putString(provider, key).apply()
    }

    /** Returns the stored key for [provider], or null if none has been set. */
    fun getApiKey(provider: String): String? = prefs.getString(provider, null)

    /** Removes the stored key for [provider]. */
    fun clearApiKey(provider: String) {
        prefs.edit().remove(provider).apply()
    }

    /** Returns true if a non-null key is stored for [provider]. */
    fun hasApiKey(provider: String): Boolean = getApiKey(provider) != null
}
