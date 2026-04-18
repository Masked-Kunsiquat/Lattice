package com.github.maskedkunisquat.lattice

import android.content.SharedPreferences
import com.github.maskedkunisquat.lattice.core.logic.KeyValueStore

/**
 * [KeyValueStore] backed by [SharedPreferences].
 *
 * [putString] uses [SharedPreferences.Editor.commit] (synchronous) so callers can
 * detect write failures via the return value, as required by [DistortionManifestStore.write].
 */
class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
