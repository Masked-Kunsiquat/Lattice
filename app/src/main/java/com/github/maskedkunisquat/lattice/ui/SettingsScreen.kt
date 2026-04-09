package com.github.maskedkunisquat.lattice.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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

private val CLOUD_PROVIDERS = listOf(
    "none" to "Local only",
    "cloud_claude" to "Claude (Cloud)",
)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToAudit: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    val showCloudDialog by viewModel.showCloudEnableDialog.collectAsStateWithLifecycle()
    val apiKeySaved by viewModel.apiKeySaved.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    LaunchedEffect(Unit) {
        viewModel.exportUri.collect { uri ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Export Journal",
                )
            )
        }
    }

    if (showCloudDialog) {
        CloudEnableWarningDialog(
            onConfirm = viewModel::confirmCloudEnable,
            onDismiss = viewModel::dismissCloudEnableDialog,
        )
    }

    var activityDialogTarget by remember { mutableStateOf<ActivityHierarchy?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        ActivityEditDialog(
            initial = null,
            onConfirm = { name, diff, cat ->
                viewModel.insertActivity(name, diff, cat)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    activityDialogTarget?.let { activity ->
        ActivityEditDialog(
            initial = activity,
            onConfirm = { name, diff, cat ->
                viewModel.updateActivity(activity.copy(taskName = name, difficulty = diff, valueCategory = cat))
                activityDialogTarget = null
            },
            onDismiss = { activityDialogTarget = null },
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Behavioral Activation",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add activity")
                    }
                }
            }

            if (activities.isEmpty()) {
                item {
                    Text(
                        "No activities yet. Tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(activities, key = { it.id }) { activity ->
                    ActivityItem(
                        activity = activity,
                        onEdit = { activityDialogTarget = activity },
                        onDelete = { viewModel.deleteActivity(activity) },
                    )
                }
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

            if (cloudProvider != "none") {
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
                TextButton(onClick = { keyVisible = !keyVisible }) {
                    Text(if (keyVisible) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall)
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

// ── Activity list item ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityItem(
    activity: ActivityHierarchy,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error)
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Surface {
            ListItem(
                headlineContent = { Text(activity.taskName) },
                supportingContent = {
                    Text("Difficulty ${activity.difficulty}/10 · ${activity.valueCategory}")
                },
                modifier = Modifier.clickable(onClick = onEdit),
            )
        }
    }
}

// ── Activity add/edit dialog ──────────────────────────────────────────────────

@Composable
private fun ActivityEditDialog(
    initial: ActivityHierarchy?,
    onConfirm: (name: String, difficulty: Int, category: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.taskName ?: "") }
    var difficulty by remember { mutableFloatStateOf((initial?.difficulty ?: 5).toFloat()) }
    var category by remember { mutableStateOf(initial?.valueCategory ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add Activity" else "Edit Activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Task name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text(
                        "Difficulty: ${difficulty.toInt()}/10",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = difficulty,
                        onValueChange = { difficulty = it },
                        valueRange = 0f..10f,
                        steps = 9,
                    )
                }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Value category") },
                    placeholder = { Text("e.g. connection, health, creativity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, difficulty.toInt(), category) },
                enabled = name.isNotBlank() && category.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
