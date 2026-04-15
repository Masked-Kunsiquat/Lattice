package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.ActivityHierarchy
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

// ── ViewModel ─────────────────────────────────────────────────────────────────

class ActivityHierarchyViewModel(app: LatticeApplication) : ViewModel() {

    private val dao = app.database.activityHierarchyDao()

    val activities: StateFlow<List<ActivityHierarchy>> =
        dao.getAllActivities().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    fun insertActivity(name: String, difficulty: Int, category: String) {
        viewModelScope.launch {
            dao.insertActivity(
                ActivityHierarchy(
                    id = UUID.randomUUID(),
                    taskName = name.trim(),
                    difficulty = difficulty,
                    valueCategory = category.trim(),
                )
            )
        }
    }

    fun updateActivity(activity: ActivityHierarchy) {
        viewModelScope.launch { dao.updateActivity(activity) }
    }

    fun deleteActivity(activity: ActivityHierarchy) {
        viewModelScope.launch { dao.deleteActivity(activity) }
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ActivityHierarchyViewModel(app) as T
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ActivityHierarchyScreen(viewModel: ActivityHierarchyViewModel) {
    val activities by viewModel.activities.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var activityDialogTarget by remember { mutableStateOf<ActivityHierarchy?>(null) }

    if (showAddDialog) {
        ActivityHierarchyEditDialog(
            initial = null,
            onConfirm = { name, diff, cat ->
                viewModel.insertActivity(name, diff, cat)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    activityDialogTarget?.let { activity ->
        ActivityHierarchyEditDialog(
            initial = activity,
            onConfirm = { name, diff, cat ->
                viewModel.updateActivity(activity.copy(taskName = name, difficulty = diff, valueCategory = cat))
                activityDialogTarget = null
            },
            onDismiss = { activityDialogTarget = null },
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("fab:add-activity"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add activity")
            }
        },
    ) { innerPadding ->
        if (activities.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("empty:activities"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No activities yet — add one with the button below",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .testTag("list:activities"),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                items(activities, key = { it.id }) { activity ->
                    ActivityHierarchyItem(
                        activity = activity,
                        onEdit = { activityDialogTarget = activity },
                        onDelete = { viewModel.deleteActivity(activity) },
                    )
                }
            }
        }
    }
}

// ── List item ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityHierarchyItem(
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

// ── Add / edit dialog ─────────────────────────────────────────────────────────

@Composable
private fun ActivityHierarchyEditDialog(
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
