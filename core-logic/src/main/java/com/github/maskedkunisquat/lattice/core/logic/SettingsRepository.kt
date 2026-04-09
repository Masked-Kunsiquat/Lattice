package com.github.maskedkunisquat.lattice.core.logic

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for user-controlled sovereignty and data-portability settings.
 *
 * Backed by [DataStore<Preferences>] so values survive process restarts.
 * All writes are suspend functions; all reads are [Flow]-based and safe to collect
 * from any coroutine scope.
 *
 * @param dataStore Injected by the Application (created once via [preferencesDataStore]).
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val CLOUD_ENABLED = booleanPreferencesKey("cloud_enabled")
        val CLOUD_PROVIDER = stringPreferencesKey("cloud_provider")
        val TRANSIT_RETENTION_DAYS = intPreferencesKey("transit_retention_days")
        val EXPORT_INCLUDE_TRANSIT_LOG = booleanPreferencesKey("export_include_transit_log")
    }

    val settings: Flow<LatticeSettings> = dataStore.data.map { prefs ->
        LatticeSettings(
            cloudEnabled = prefs[Keys.CLOUD_ENABLED] ?: false,
            cloudProvider = prefs[Keys.CLOUD_PROVIDER] ?: "none",
            transitRetentionDays = prefs[Keys.TRANSIT_RETENTION_DAYS] ?: 90,
            exportIncludeTransitLog = prefs[Keys.EXPORT_INCLUDE_TRANSIT_LOG] ?: true,
        )
    }

    suspend fun setCloudEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CLOUD_ENABLED] = enabled }
    }

    suspend fun setCloudProvider(providerId: String) {
        dataStore.edit { it[Keys.CLOUD_PROVIDER] = providerId }
    }

    suspend fun setTransitRetentionDays(days: Int) {
        dataStore.edit { it[Keys.TRANSIT_RETENTION_DAYS] = days }
    }

    suspend fun setExportIncludeTransitLog(include: Boolean) {
        dataStore.edit { it[Keys.EXPORT_INCLUDE_TRANSIT_LOG] = include }
    }
}

data class LatticeSettings(
    val cloudEnabled: Boolean = false,
    val cloudProvider: String = "none",
    val transitRetentionDays: Int = 90,
    val exportIncludeTransitLog: Boolean = true,
)
