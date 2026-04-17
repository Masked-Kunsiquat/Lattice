package com.github.maskedkunisquat.lattice.ui

import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.github.maskedkunisquat.lattice.core.logic.AffectiveManifest
import com.github.maskedkunisquat.lattice.core.logic.ModelLoadState
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.work.WorkInfo
import com.github.maskedkunisquat.lattice.BuildConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import com.github.maskedkunisquat.lattice.core.logic.ExportManager
import com.github.maskedkunisquat.lattice.ui.SettingsViewModel.Companion.CLOUD_NONE_PROVIDER

private val CLOUD_PROVIDERS = listOf(
    CLOUD_NONE_PROVIDER to "Local only",
    "cloud_claude" to "Claude (Cloud)",
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToAudit: () -> Unit,
    onNavigateToActivities: () -> Unit,
    onNavigateToDebugSeed: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val showCloudDialog by viewModel.showCloudEnableDialog.collectAsStateWithLifecycle()
    val apiKeySaved by viewModel.apiKeySaved.collectAsStateWithLifecycle()
    val modelLoadState by viewModel.modelLoadState.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadWorkInfo by viewModel.downloadWorkInfo.collectAsStateWithLifecycle(null)
    val manifestState: AffectiveManifest? by viewModel.manifest.collectAsStateWithLifecycle()
    val isResetting by viewModel.isResetting.collectAsStateWithLifecycle()

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

    if (showCloudDialog) {
        CloudEnableWarningDialog(
            onConfirm = viewModel::confirmCloudEnable,
            onDismiss = viewModel::dismissCloudEnableDialog,
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ── Sovereignty ──────────────────────────────────────────────────
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

            // ── Local Model ──────────────────────────────────────────────────
            item { SectionHeader("Local Model") }
            item {
                LocalModelSection(
                    modelLoadState = modelLoadState,
                    downloadProgress = downloadProgress,
                    downloadWorkInfo = downloadWorkInfo,
                    onDownload = viewModel::downloadModel,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // ── Personalization ──────────────────────────────────────────────
            item { SectionHeader("Personalization") }
            item {
                PersonalizationSection(
                    personalizationEnabled = settings.personalizationEnabled,
                    manifest = manifestState,
                    isResetting = isResetting,
                    onToggle = viewModel::setPersonalizationEnabled,
                    onReset = viewModel::resetPersonalization,
                )
            }

            // ── Audit Trail ──────────────────────────────────────────────────
            item { SectionHeader("Audit Trail") }
            item {
                ListItem(
                    headlineContent = { Text("View Audit Log") },
                    supportingContent = { Text("See when data has left this device") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onNavigateToAudit),
                )
            }

            // ── Behavioral Activation ────────────────────────────────────────
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

            // ── Data Portability ─────────────────────────────────────────────
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

            // ── Debug (debug builds only) ────────────────────────────────────
            if (BuildConfig.DEBUG) {
                item { SectionHeader("Debug") }
                item {
                    ListItem(
                        headlineContent = { Text("Seed Data") },
                        supportingContent = { Text("Inject and clear persona datasets") },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable(onClick = onNavigateToDebugSeed),
                    )
                }
            }

            // ── About ────────────────────────────────────────────────────────
            item { SectionHeader("About") }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    AboutRow("App version", "1.0")
                    AboutRow("Schema version", ExportManager.SCHEMA_VERSION)
                    AboutRow("Embedding model", "snowflake-arctic-embed-xs")
                }
            }
        }
    }
}

@Composable
private fun LocalModelSection(
    modelLoadState: ModelLoadState,
    downloadProgress: Float,
    downloadWorkInfo: WorkInfo?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDownloading = downloadWorkInfo?.state == WorkInfo.State.RUNNING ||
            downloadWorkInfo?.state == WorkInfo.State.ENQUEUED

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val statusText = when {
            isDownloading -> "Downloading model… ${(downloadProgress * 100).toInt()}%"
            modelLoadState == ModelLoadState.IDLE -> "Not started"
            modelLoadState == ModelLoadState.COPYING_SHARDS -> "Copying model shards…"
            modelLoadState == ModelLoadState.LOADING_SESSION -> "Loading model session…"
            modelLoadState == ModelLoadState.READY -> "Ready"
            modelLoadState == ModelLoadState.ERROR -> "Not found / Failed to load"
            else -> "Not started"
        }
        val statusColor = when (modelLoadState) {
            ModelLoadState.READY  -> Color(0xFF2E7D32)
            ModelLoadState.ERROR  -> Color(0xFFB00020)
            else                  -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Gemma 3 1B (local fallback)", style = MaterialTheme.typography.bodyLarge)
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
            if ((modelLoadState == ModelLoadState.ERROR || modelLoadState == ModelLoadState.IDLE) && !isDownloading) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.padding(start = 8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("Download", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        when {
            isDownloading -> {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Downloading ~700 MB to device storage. You can close the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            modelLoadState == ModelLoadState.COPYING_SHARDS -> {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Preparing model files. Please wait.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            modelLoadState == ModelLoadState.LOADING_SESSION -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Optimizing model for your hardware. This may take a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            modelLoadState == ModelLoadState.ERROR -> {
                Text(
                    "The model file is missing or corrupted. You can download it now (689MB) or push it via ADB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> Unit
        }
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

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Personalization", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Improves mood detection over time using your corrections. " +
                    "All learning happens on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = personalizationEnabled, onCheckedChange = onToggle)
        }

        Spacer(Modifier.height(8.dp))

        val trainedCount = manifest?.trainedOnCount ?: 0
        val lastTimestamp = manifest?.lastTrainingTimestamp ?: 0L
        AboutRow("Trained on", "$trainedCount corrections")
        AboutRow("Last updated", formatRelativeTimestamp(lastTimestamp))

        // 3.6-g: only show the reset button when there is actually something to reset,
        // and disable it while a reset is already in progress (double-tap guard).
        if (trainedCount > 0 || isResetting) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showResetDialog = true },
                enabled = !isResetting,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (isResetting) "Resetting…" else "Reset personalization")
            }
        }
    }
}

private fun formatRelativeTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L              -> "Just now"
        diff < 3_600_000L           -> "${diff / 60_000} min ago"
        diff < 86_400_000L          -> "${diff / 3_600_000} hr ago"
        diff < 2 * 86_400_000L      -> "Yesterday"
        else                        -> (diff / 86_400_000).let { d -> if (d == 1L) "1 day ago" else "$d days ago" }
    }
}

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    color = if (cloudEnabled) Color(0xFFF59E0B)
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

// ── Cloud enable warning dialog ───────────────────────────────────────────────

@Composable
private fun CloudEnableWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null,
                tint = Color(0xFFF59E0B))
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
                Text("Enable", color = Color(0xFFF59E0B))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep local") }
        },
    )
}
