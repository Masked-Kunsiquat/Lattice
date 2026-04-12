package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.CloudCredentialStore
import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.logic.ExportManager
import com.github.maskedkunisquat.lattice.core.logic.LatticeSettings
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val activityDao: ActivityHierarchyDao,
    private val exportManager: ExportManager,
    private val cloudCredentialStore: CloudCredentialStore,
    val modelLoadState: StateFlow<ModelLoadState>,
    val copyProgress: StateFlow<Float>,
) : ViewModel() {

    val settings: StateFlow<LatticeSettings> = settingsRepository.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), LatticeSettings()
    )

    val activities: StateFlow<List<ActivityHierarchy>> = activityDao.getAllActivities().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    private val _showCloudEnableDialog = MutableStateFlow(false)
    val showCloudEnableDialog: StateFlow<Boolean> = _showCloudEnableDialog.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    private val _exportShareIntent = MutableSharedFlow<Intent>()
    val exportShareIntent = _exportShareIntent.asSharedFlow()

    // API key state: true when a key is stored for the cloud_claude provider
    private val _apiKeySaved = MutableStateFlow(cloudCredentialStore.hasApiKey(CLOUD_CLAUDE_PROVIDER))
    val apiKeySaved: StateFlow<Boolean> = _apiKeySaved.asStateFlow()

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        cloudCredentialStore.setApiKey(CLOUD_CLAUDE_PROVIDER, trimmed)
        _apiKeySaved.value = true
    }

    fun clearApiKey() {
        cloudCredentialStore.clearApiKey(CLOUD_CLAUDE_PROVIDER)
        _apiKeySaved.value = false
    }

    fun requestCloudEnable() { _showCloudEnableDialog.update { true } }

    fun confirmCloudEnable() {
        _showCloudEnableDialog.update { false }
        viewModelScope.launch { settingsRepository.setCloudEnabled(true) }
    }

    fun dismissCloudEnableDialog() { _showCloudEnableDialog.update { false } }

    fun setCloudDisabled() {
        viewModelScope.launch { settingsRepository.setCloudEnabled(false) }
    }

    fun setCloudProvider(providerId: String) {
        viewModelScope.launch { settingsRepository.setCloudProvider(providerId) }
    }

    fun insertActivity(name: String, difficulty: Int, category: String) {
        viewModelScope.launch {
            activityDao.insertActivity(
                ActivityHierarchy(UUID.randomUUID(), name.trim(), difficulty, category.trim())
            )
        }
    }

    fun updateActivity(activity: ActivityHierarchy) {
        val normalized = activity.copy(
            taskName = activity.taskName.trim(),
            valueCategory = activity.valueCategory.trim(),
        )
        viewModelScope.launch { activityDao.updateActivity(normalized) }
    }

    fun deleteActivity(activity: ActivityHierarchy) {
        viewModelScope.launch { activityDao.deleteActivity(activity) }
    }

    fun exportJournal() {
        viewModelScope.launch {
            try {
                val uri = exportManager.exportToFile()
                _exportShareIntent.emit(exportManager.buildShareIntent(uri))
            } catch (e: Exception) {
                _snackbarMessage.emit("Export failed: ${e.message}")
            }
        }
    }

    companion object {
        const val CLOUD_CLAUDE_PROVIDER = "cloud_claude"
        const val CLOUD_NONE_PROVIDER = "none"

        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SettingsViewModel(
                    settingsRepository = app.settingsRepository,
                    activityDao = app.database.activityHierarchyDao(),
                    exportManager = app.exportManager,
                    cloudCredentialStore = app.cloudCredentialStore,
                    modelLoadState = app.localFallbackProvider.modelLoadState,
                    copyProgress = app.localFallbackProvider.copyProgress,
                ) as T
        }
    }
}
