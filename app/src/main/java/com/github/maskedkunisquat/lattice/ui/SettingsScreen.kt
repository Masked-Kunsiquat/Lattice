package com.github.maskedkunisquat.lattice.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.github.maskedkunisquat.lattice.ui.theme.StatusAmber
import com.github.maskedkunisquat.lattice.ui.theme.StatusGreen
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.github.maskedkunisquat.lattice.BuildConfig
import com.github.maskedkunisquat.lattice.core.logic.AffectiveManifest
import com.github.maskedkunisquat.lattice.core.logic.ExportManager
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import com.github.maskedkunisquat.lattice.ui.SettingsViewModel.Companion.CLOUD_NONE_PROVIDER

private val CLOUD_PROVIDERS = listOf(
    CLOUD_NONE_PROVIDER to "Local only",
    "cloud_claude" to "Claude (Cloud)",
)

// ── Top-level settings screen ─────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToInference: () -> Unit,
    onNavigateToPersonalization: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    onNavigateToDebugSeed: () -> Unit = {},
) {
    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Inference") }
            item {
                ListItem(
                    headlineContent = { Text("Local model & cloud") },
                    supportingContent = { Text("On-device Gemma 3 1B and cloud processing settings") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onNavigateToInference),
                )
            }

            item { SectionHeader("Personalization") }
            item {
                ListItem(
                    headlineContent = { Text("Mood learning & activities") },
                    supportingContent = { Text("On-device mood adaptation and behavioral activation") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onNavigateToPersonalization),
                )
            }

            item { SectionHeader("Privacy & Data") }
            item {
                ListItem(
                    headlineContent = { Text("Audit log & export") },
                    supportingContent = { Text("Review data transfers and export your journal") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onNavigateToPrivacy),
                )
            }

            item { SectionHeader("About") }
            item {
                val loadedModel by viewModel.loadedModelName.collectAsStateWithLifecycle()
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AboutRow("App version", "1.0")
                    AboutRow("Schema version", ExportManager.SCHEMA_VERSION)
                    AboutRow("Embedding model", "snowflake-arctic-embed-xs")
                    AboutRow("Loaded model", loadedModel ?: "None")
                }
            }

            if (BuildConfig.DEBUG) {
                item { SectionHeader("Debug") }
                item {
                    ListItem(
                        headlineContent = { Text("Seed Data") },
                        supportingContent = { Text("Inject and clear persona datasets") },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onNavigateToDebugSeed),
                    )
                }
            }
        }
    }
}

// ── Inference sub-screen ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val showCloudDialog by viewModel.showCloudEnableDialog.collectAsStateWithLifecycle()
    val apiKeySaved by viewModel.apiKeySaved.collectAsStateWithLifecycle()
    val modelLoadState by viewModel.modelLoadState.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadWorkInfo by viewModel.downloadWorkInfo.collectAsStateWithLifecycle(null)
    val cbtDownloadProgress by viewModel.cbtDownloadProgress.collectAsStateWithLifecycle()
    val cbtDownloadWorkInfo by viewModel.cbtDownloadWorkInfo.collectAsStateWithLifecycle(null)

    val context = LocalContext.current

    // Request POST_NOTIFICATIONS before enqueueing the download worker so the
    // foreground service progress notification is permitted. Download proceeds
    // regardless of the outcome — in-app progress is always visible.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.downloadModel()
    }

    val onDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadModel()
        }
    }

    val onCbtDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.downloadCbtModel()
        }
    }

    if (showCloudDialog) {
        CloudEnableWarningDialog(
            onConfirm = viewModel::confirmCloudEnable,
            onDismiss = viewModel::dismissCloudEnableDialog,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inference") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Local Model") }
            item {
                LocalModelSection(
                    useCbtModel = settings.useCbtModel,
                    onUseCbtModelChange = viewModel::setUseCbtModel,
                    modelLoadState = modelLoadState,
                    downloadProgress = downloadProgress,
                    downloadWorkInfo = downloadWorkInfo,
                    onDownload = onDownload,
                    cbtDownloadProgress = cbtDownloadProgress,
                    cbtDownloadWorkInfo = cbtDownloadWorkInfo,
                    onCbtDownload = onCbtDownload,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { SectionHeader("Sovereignty") }
            item {
                SovereigntySection(
                    cloudEnabled = settings.cloudEnabled,
                    cloudProvider = settings.cloudProvider,
                    apiKeySaved = apiKeySaved,
                    onCloudToggle = { enabled ->
                        if (enabled) viewModel.requestCloudEnable() else viewModel.setCloudDisabled()
                    },
                    onProviderChange = viewModel::setCloudProvider,
                    onApiKeySave = viewModel::setApiKey,
                    onApiKeyClear = viewModel::clearApiKey,
                )
            }
        }
    }
}

// ── Personalization sub-screen ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToActivities: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val manifestState by viewModel.manifest.collectAsStateWithLifecycle()
    val isResetting by viewModel.isResetting.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personalization") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Mood Learning") }
            item {
                PersonalizationSection(
                    personalizationEnabled = settings.personalizationEnabled,
                    manifest = manifestState,
                    isResetting = isResetting,
                    onToggle = viewModel::setPersonalizationEnabled,
                    onReset = viewModel::resetPersonalization,
                )
            }

            item { SectionHeader("Behavioral Activation") }
            item {
                val activityCount = activities.size
                ListItem(
                    headlineContent = { Text("Manage Activities") },
                    supportingContent = {
                        Text(
                            when (activityCount) {
                                0    -> "No activities yet"
                                1    -> "1 activity"
                                else -> "$activityCount activities"
                            }
                        )
                    },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onNavigateToActivities),
                )
            }
        }
    }
}

// ── Privacy & Data sub-screen ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDataSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onNavigateToAudit: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    LaunchedEffect(Unit) {
        viewModel.exportShareIntent.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w("SettingsScreen", "No app available to handle export intent", e)
                snackbarHostState.showSnackbar("No app found to open the export file")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacy & Data") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Audit Trail") }
            item {
                ListItem(
                    headlineContent = { Text("View Audit Log") },
                    supportingContent = { Text("See when data has left this device") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onNavigateToAudit),
                )
            }

            item { SectionHeader("Data Portability") }
            item {
                Button(
                    onClick = viewModel::exportJournal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Journal")
                }
            }
        }
    }
}

// ── UI Components ────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Local Model section ───────────────────────────────────────────────────────

@Composable
private fun LocalModelSection(
    useCbtModel: Boolean,
    onUseCbtModelChange: (Boolean) -> Unit,
    modelLoadState: ModelLoadState,
    downloadProgress: Float,
    downloadWorkInfo: WorkInfo?,
    onDownload: () -> Unit,
    cbtDownloadProgress: Float,
    cbtDownloadWorkInfo: WorkInfo?,
    onCbtDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDownloading = downloadWorkInfo?.state == WorkInfo.State.RUNNING ||
            downloadWorkInfo?.state == WorkInfo.State.ENQUEUED
    val isCbtDownloading = cbtDownloadWorkInfo?.state == WorkInfo.State.RUNNING ||
            cbtDownloadWorkInfo?.state == WorkInfo.State.ENQUEUED

    val context = LocalContext.current
    val notificationsBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    } else false

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // --- Selection ---
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Preferred Model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)

            // CBT Radio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCbtDownloading && !isDownloading) { onUseCbtModelChange(true) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = useCbtModel,
                    onClick = { onUseCbtModelChange(true) },
                    enabled = !isCbtDownloading && !isDownloading
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("CBT Fine-tuned", style = MaterialTheme.typography.bodyLarge)
                    Text("Optimized for cognitive reframing (~1.03 GB)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Base Radio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCbtDownloading && !isDownloading) { onUseCbtModelChange(false) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = !useCbtModel,
                    onClick = { onUseCbtModelChange(false) },
                    enabled = !isCbtDownloading && !isDownloading
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("Base Model", style = MaterialTheme.typography.bodyLarge)
                    Text("General purpose Gemma 3 1B (~580 MB)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // --- Action Area (Download/Progress) ---
        val activeDownload = if (useCbtModel) isCbtDownloading else isDownloading
        val activeProgress = if (useCbtModel) cbtDownloadProgress else downloadProgress

        // We show the download button if the engine is IDLE or ERROR.
        val showDownloadButton = modelLoadState == ModelLoadState.IDLE || modelLoadState == ModelLoadState.ERROR

        if (activeDownload) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(
                    progress = { activeProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Downloading… ${(activeProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (showDownloadButton) {
            Button(
                onClick = if (useCbtModel) onCbtDownload else onDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download ${if (useCbtModel) "CBT" else "Base"} Model")
            }
        }

        // --- Engine Status ---
        val statusText = when {
            activeDownload -> "Downloading…"
            modelLoadState == ModelLoadState.IDLE -> "Not downloaded"
            modelLoadState == ModelLoadState.COPYING_MODEL -> "Copying…"
            modelLoadState == ModelLoadState.LOADING_SESSION -> "Optimizing…"
            modelLoadState == ModelLoadState.READY -> "Ready"
            modelLoadState == ModelLoadState.ERROR -> "Load failed"
            else -> "Offline"
        }
        val statusColor = if (modelLoadState == ModelLoadState.READY)
            StatusGreen
        else if (modelLoadState == ModelLoadState.ERROR)
            MaterialTheme.colorScheme.error
        else
            MaterialTheme.colorScheme.onSurfaceVariant

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Engine Status", style = MaterialTheme.typography.bodyMedium)
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor, fontWeight = FontWeight.Bold)
        }

        if (notificationsBlocked && (isDownloading || isCbtDownloading)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "Notifications are off — no status bar progress.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Enable", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ── Sovereignty section ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SovereigntySection(
    cloudEnabled: Boolean,
    cloudProvider: String,
    apiKeySaved: Boolean,
    onCloudToggle: (Boolean) -> Unit,
    onProviderChange: (String) -> Unit,
    onApiKeySave: (String) -> Unit,
    onApiKeyClear: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cloud processing", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (cloudEnabled) "Data may leave this device" else "All processing is local",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cloudEnabled) StatusAmber
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = cloudEnabled, onCheckedChange = onCloudToggle)
        }

        if (cloudEnabled) {
            Spacer(Modifier.height(12.dp))
            var expanded by remember { mutableStateOf(false) }
            val selectedLabel = CLOUD_PROVIDERS.find { it.first == cloudProvider }?.second
                ?: cloudProvider

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    CLOUD_PROVIDERS.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onProviderChange(id)
                                expanded = false
                            },
                        )
                    }
                }
            }

            if (cloudProvider != CLOUD_NONE_PROVIDER) {
                Spacer(Modifier.height(12.dp))
                ApiKeySection(
                    apiKeySaved = apiKeySaved,
                    onSave = onApiKeySave,
                    onClear = onApiKeyClear,
                )
            }
        }
    }
}

@Composable
private fun ApiKeySection(
    apiKeySaved: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = null,
                    tint = if (apiKeySaved) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (apiKeySaved) "API key saved" else "No API key set",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (apiKeySaved) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (apiKeySaved) {
                TextButton(
                    onClick = onClear,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Clear") }
            }
        }

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (apiKeySaved) "Replace API key" else "Enter API key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.VisibilityOff
                                      else Icons.Default.Visibility,
                        contentDescription = if (keyVisible) "Hide API key" else "Show API key",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                onSave(keyInput)
                keyInput = ""
                keyVisible = false
            },
            enabled = keyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save key") }
    }
}

// ── Personalization section ───────────────────────────────────────────────────

@Composable
private fun PersonalizationSection(
    personalizationEnabled: Boolean,
    manifest: AffectiveManifest?,
    isResetting: Boolean,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset personalization?") },
            text = {
                Text(
                    "All learned preferences will be deleted and the model will restart " +
                    "from its default state on next launch. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onReset(); showResetDialog = false },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable personalization", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (personalizationEnabled) "Learning your mood patterns"
                    else "Using default general model",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = personalizationEnabled, onCheckedChange = onToggle)
        }

        if (personalizationEnabled && manifest != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Model state", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Last trained ${formatTimestamp(manifest.lastTrainingTimestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { showResetDialog = true },
                    enabled = !isResetting,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (isResetting) Text("Resetting…") else Text("Reset")
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000      -> "Just now"
        diff < 3_600_000   -> "${diff / 60_000}m ago"
        diff < 86_400_000  -> "${diff / 3_600_000}h ago"
        else                   -> (diff / 86_400_000).let { d -> if (d == 1L) "1 day ago" else "$d days ago" }
    }
}

// ── Cloud enable warning dialog ───────────────────────────────────────────────

@Composable
private fun CloudEnableWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null, tint = StatusAmber)
        },
        title = { Text("Enable cloud processing?") },
        text = {
            Text(
                "Your journal entries will be sent to a remote AI provider for processing. " +
                "All content is masked before leaving this device — names and identifiers are " +
                "replaced with anonymous tokens — but the masked text will leave your device. " +
                "\n\nA transit log is recorded each time data is sent."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Enable", color = StatusAmber)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep local") }
        },
    )
}
