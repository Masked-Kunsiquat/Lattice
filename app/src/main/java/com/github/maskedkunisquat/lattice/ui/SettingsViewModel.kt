package com.github.maskedkunisquat.lattice.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.CloudCredentialStore
import com.github.maskedkunisquat.lattice.core.data.dao.ActivityHierarchyDao
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.logic.AffectiveManifest
import com.github.maskedkunisquat.lattice.core.logic.AffectiveManifestStore
import com.github.maskedkunisquat.lattice.core.logic.ExportManager
import com.github.maskedkunisquat.lattice.core.logic.LatticeSettings
import com.github.maskedkunisquat.lattice.core.logic.TrainingCoordinator
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.core.logic.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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
    private val context: Context,
    // 3.6-f: injected singleton instead of constructing ad-hoc in resetPersonalization
    private val trainingCoordinator: TrainingCoordinator,
    // 3.6-e: outlives the ViewModel so reset can't be cancelled mid-flight by navigation
    private val applicationScope: CoroutineScope,
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

    // 3.6-g: true while resetPersonalization is running — gates the UI button and
    // prevents a second concurrent reset from being launched by double-tap.
    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting.asStateFlow()

    // API key state: true when a key is stored for the cloud_claude provider
    private val _apiKeySaved = MutableStateFlow(cloudCredentialStore.hasApiKey(CLOUD_CLAUDE_PROVIDER))
    val apiKeySaved: StateFlow<Boolean> = _apiKeySaved.asStateFlow()

    // Affective MLP manifest — reactive: re-emits whenever the SharedPreferences key changes
    val manifest: StateFlow<AffectiveManifest?> = callbackFlow {
        val prefs = context.getSharedPreferences(AffectiveManifestStore.PREFS_NAME, Context.MODE_PRIVATE)
        trySend(AffectiveManifestStore.read(prefs))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AffectiveManifestStore.PREF_KEY) trySend(AffectiveManifestStore.read(prefs))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

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

    fun setPersonalizationEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPersonalizationEnabled(enabled) }
    }

    /**
     * Delegates to [TrainingCoordinator.resetPersonalization] — the single authoritative
     * path shared with the `resetPersonalization` instrumented test.
     *
     * Runs on [applicationScope] (3.6-e) so navigation away from Settings cannot cancel
     * a reset in progress. Guards against double-tap via [_isResetting] (3.6-g).
     * Re-schedules training afterward if personalization is still enabled (3.6-a).
     */
    fun resetPersonalization() {
        if (_isResetting.value) return
        applicationScope.launch {
            _isResetting.value = true
            try {
                trainingCoordinator.resetPersonalization(context)
                // manifest StateFlow updates reactively via the SharedPreferences listener
                // 3.6-a: distinctUntilChanged won't re-fire since the toggle hasn't changed,
                // so re-schedule here when personalization is still on.
                if (settings.value.personalizationEnabled) {
                    trainingCoordinator.scheduleIfNeeded(context)
                }
            } finally {
                _isResetting.value = false
            }
        }
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
                    context = app.applicationContext,
                    trainingCoordinator = app.trainingCoordinator,
                    applicationScope = app.applicationScope,
                ) as T
        }
    }
}
