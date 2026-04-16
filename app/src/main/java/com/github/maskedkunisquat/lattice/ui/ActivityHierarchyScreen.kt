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
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// ── MVI contract ──────────────────────────────────────────────────────────────

sealed class ActivityIntent {
    data class Insert(val name: String, val difficulty: Int, val category: String) : ActivityIntent()
    data class Update(val activity: ActivityHierarchy) : ActivityIntent()
    data class Delete(val activity: ActivityHierarchy) : ActivityIntent()
    object OpenAddDialog : ActivityIntent()
    object DismissDialog : ActivityIntent()
    data class OpenEditDialog(val activity: ActivityHierarchy) : ActivityIntent()
}

data class ActivityScreenState(
    val activities: List<ActivityHierarchy> = emptyList(),
    val showAddDialog: Boolean = false,
    val editTarget: ActivityHierarchy? = null,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class ActivityHierarchyViewModel(app: LatticeApplication) : ViewModel() {

    private val dao = app.database.activityHierarchyDao()

    private val _uiState = MutableStateFlow(ActivityScreenState())
    val uiState: StateFlow<ActivityScreenState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            dao.getAllActivities().collect { activities ->
                _uiState.update { it.copy(activities = activities) }
            }
        }
    }

    fun processIntent(intent: ActivityIntent) {
        when (intent) {
            is ActivityIntent.Insert -> viewModelScope.launch {
                dao.insertActivity(
                    ActivityHierarchy(
                        id = UUID.randomUUID(),
                        taskName = intent.name.trim(),
                        difficulty = intent.difficulty,
                        valueCategory = intent.category.trim(),
                    )
                )
                _uiState.update { it.copy(showAddDialog = false) }
            }
            is ActivityIntent.Update -> viewModelScope.launch {
                dao.updateActivity(
                    intent.activity.copy(
                        taskName = intent.activity.taskName.trim(),
                        valueCategory = intent.activity.valueCategory.trim(),
                    )
                )
                _uiState.update { it.copy(editTarget = null) }
            }
            is ActivityIntent.Delete -> viewModelScope.launch {
                dao.deleteActivity(intent.activity)
            }
            ActivityIntent.OpenAddDialog -> _uiState.update { it.copy(showAddDialog = true) }
            ActivityIntent.DismissDialog -> _uiState.update { it.copy(showAddDialog = false, editTarget = null) }
            is ActivityIntent.OpenEditDialog -> _uiState.update { it.copy(editTarget = intent.activity) }
        }
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.showAddDialog) {
        ActivityHierarchyEditDialog(
            initial = null,
            onConfirm = { name, diff, cat ->
                viewModel.processIntent(ActivityIntent.Insert(name, diff, cat))
            },
            onDismiss = { viewModel.processIntent(ActivityIntent.DismissDialog) },
        )
    }

    uiState.editTarget?.let { activity ->
        ActivityHierarchyEditDialog(
            initial = activity,
            onConfirm = { name, diff, cat ->
                viewModel.processIntent(
                    ActivityIntent.Update(activity.copy(taskName = name, difficulty = diff, valueCategory = cat))
                )
            },
            onDismiss = { viewModel.processIntent(ActivityIntent.DismissDialog) },
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.processIntent(ActivityIntent.OpenAddDialog) },
                modifier = Modifier.testTag("fab:add-activity"),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add activity")
            }
        },
    ) { innerPadding ->
        if (uiState.activities.isEmpty()) {
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
                items(uiState.activities, key = { it.id }) { activity ->
                    ActivityHierarchyItem(
                        activity = activity,
                        onEdit = { viewModel.processIntent(ActivityIntent.OpenEditDialog(activity)) },
                        onDelete = { viewModel.processIntent(ActivityIntent.Delete(activity)) },
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
                trailingContent = {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete activity",
                        )
                    }
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
    var name by rememberSaveable { mutableStateOf(initial?.taskName ?: "") }
    var difficulty by rememberSaveable { mutableStateOf((initial?.difficulty ?: 5).toFloat()) }
    var category by rememberSaveable { mutableStateOf(initial?.valueCategory ?: "") }

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
