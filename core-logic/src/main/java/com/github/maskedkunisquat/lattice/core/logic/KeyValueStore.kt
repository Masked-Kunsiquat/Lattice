package com.github.maskedkunisquat.lattice.core.logic

/**
 * Minimal key-value persistence abstraction used by [DistortionManifestStore].
 *
 * Decouples [DistortionManifestStore] from [android.content.SharedPreferences],
 * keeping `:core-logic` free of Android framework imports.
 */
interface KeyValueStore {
    /** Returns the stored string for [key], or `null` if absent. */
    fun getString(key: String): String?

    /**
     * Stores [value] under [key].
     *
     * @return `true` if the write was durably committed (synchronous), `false` on failure.
     */
    fun putString(key: String, value: String): Boolean

    /** Removes the entry for [key]. */
    fun remove(key: String)
}
