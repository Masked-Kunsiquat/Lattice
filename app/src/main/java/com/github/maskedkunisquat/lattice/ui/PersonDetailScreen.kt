package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.data.model.JournalEntry
import com.github.maskedkunisquat.lattice.core.data.model.Person
import com.github.maskedkunisquat.lattice.core.data.model.PhoneNumber
import com.github.maskedkunisquat.lattice.core.data.model.RelationshipType
import com.github.maskedkunisquat.lattice.core.logic.PersonWithPhones
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

private fun RelationshipType.displayLabel(): String =
    name.split('_').joinToString(" ") { word ->
        word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    viewModel: PersonDetailViewModel,
    onBack: () -> Unit,
    onOpenEntry: (UUID) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.deletedEvent.collect { onBack() }
    }

    when (val s = state) {
        is PersonDetailState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is PersonDetailState.NotFound -> Unit // handled by LaunchedEffect above
        is PersonDetailState.Found -> {
            PersonDetailContent(
                personWithPhones = s.personWithPhones,
                entries = s.entries,
                onBack = onBack,
                onOpenEntry = onOpenEntry,
                onToggleFavorite = viewModel::toggleFavorite,
                onSavePerson = viewModel::savePerson,
                onDeletePerson = viewModel::deletePerson,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonDetailContent(
    personWithPhones: PersonWithPhones,
    entries: List<JournalEntry>,
    onBack: () -> Unit,
    onOpenEntry: (UUID) -> Unit,
    onToggleFavorite: () -> Unit,
    onSavePerson: (Person, List<PhoneNumber>) -> Unit,
    onDeletePerson: () -> Unit,
) {
    val person = personWithPhones.person
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove person?") },
            text = { Text("${person.firstName} and all their mentions will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = { showDeleteConfirm = false; onDeletePerson() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showEditSheet) {
        EditPersonSheet(
            personWithPhones = personWithPhones,
            onDismiss = { showEditSheet = false },
            onSave = { updatedPerson, updatedPhones ->
                onSavePerson(updatedPerson, updatedPhones)
                showEditSheet = false
            },
        )
    }

    val displayName = person.nickname?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(
            person.firstName?.takeIf { it.isNotBlank() },
            person.lastName?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "Unnamed" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditSheet = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Vibe score arc card
            VibeArcCard(
                vibeScore = person.vibeScore,
                entryCount = entries.size,
                modifier = Modifier.fillMaxWidth(),
            )

            // Relationship + favorite row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SuggestionChip(
                    onClick = { showEditSheet = true },
                    label = {
                        Text(person.relationshipType.displayLabel())
                    },
                )
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (person.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (person.isFavorite) "Remove favourite" else "Add favourite",
                        tint = if (person.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // Phone numbers section
            Text(
                "Phone numbers",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (personWithPhones.phoneNumbers.isEmpty()) {
                Text(
                    "No phone numbers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                personWithPhones.phoneNumbers.forEach { phone ->
                    ListItem(
                        headlineContent = { Text(phone.rawNumber) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider()

            // Journal entries section
            Text(
                "Journal entries (${entries.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entries.isEmpty()) {
                Text(
                    "No entries yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                entries.forEach { entry ->
                    MentionedEntryRow(
                        entry = entry,
                        onClick = { onOpenEntry(entry.id) },
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun VibeArcCard(
    vibeScore: Float,
    entryCount: Int,
    modifier: Modifier = Modifier,
) {
    val arcColor = when {
        vibeScore > 0.3f -> MaterialTheme.colorScheme.tertiary
        vibeScore < -0.3f -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
        ) {
            val strokeWidth = 14.dp.toPx()
            val radius = (size.width / 2f) * 0.75f
            val cx = size.width / 2f
            val cy = size.height
            val topLeft = Offset(cx - radius, cy - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // Background track (top semicircle)
            drawArc(
                color = trackColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = topLeft,
                size = arcSize,
            )

            // Filled portion up to score
            val fraction = ((vibeScore + 1f) / 2f).coerceIn(0f, 1f)
            if (fraction > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = 180f,
                    sweepAngle = fraction * 180f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    topLeft = topLeft,
                    size = arcSize,
                )
            }

            // Dot at current score position
            val dotAngleRad = Math.toRadians((180.0 + fraction * 180.0))
            val dotX = cx + radius * cos(dotAngleRad).toFloat()
            val dotY = cy + radius * sin(dotAngleRad).toFloat()
            drawCircle(color = arcColor, radius = 8.dp.toPx(), center = Offset(dotX, dotY))
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "%.2f".format(vibeScore),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Based on $entryCount ${if (entryCount == 1) "entry" else "entries"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MentionedEntryRow(
    entry: JournalEntry,
    onClick: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    ListItem(
        overlineContent = {
            Text(
                "${fmt.format(Date(entry.timestamp))} · ${entry.moodLabel.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }}",
                style = MaterialTheme.typography.labelSmall,
            )
        },
        headlineContent = {
            Text(
                entry.content ?: "Mood log",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPersonSheet(
    personWithPhones: PersonWithPhones,
    onDismiss: () -> Unit,
    onSave: (Person, List<PhoneNumber>) -> Unit,
) {
    val person = personWithPhones.person
    var firstName by remember(personWithPhones) { mutableStateOf(person.firstName) }
    var lastName by remember(personWithPhones) { mutableStateOf(person.lastName ?: "") }
    var nickname by remember(personWithPhones) { mutableStateOf(person.nickname ?: "") }
    var relationship by remember(personWithPhones) { mutableStateOf(person.relationshipType) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    var phones by remember(personWithPhones) { mutableStateOf(personWithPhones.phoneNumbers.toList()) }
    var newPhoneNumber by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit person", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it },
                label = { Text("First name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it },
                label = { Text("Last name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = relationship.displayLabel(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Relationship") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    RelationshipType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayLabel()) },
                            onClick = { relationship = type; dropdownExpanded = false },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Phone numbers", style = MaterialTheme.typography.labelMedium)

            phones.forEach { phone ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        phone.rawNumber,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { phones = phones - phone }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove number",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newPhoneNumber,
                    onValueChange = { newPhoneNumber = it },
                    label = { Text("Add number") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        val raw = newPhoneNumber.trim()
                        if (raw.isNotBlank()) {
                            phones = phones + PhoneNumber(
                                id = UUID.randomUUID(),
                                personId = person.id,
                                rawNumber = raw,
                                normalizedNumber = raw.filter { it.isDigit() || it == '+' },
                            )
                            newPhoneNumber = ""
                        }
                    },
                    enabled = newPhoneNumber.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add number")
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            person.copy(
                                firstName = firstName.trim(),
                                lastName = lastName.trim().ifBlank { null },
                                nickname = nickname.trim().ifBlank { null },
                                relationshipType = relationship,
                            ),
                            phones,
                        )
                    },
                    enabled = firstName.isNotBlank(),
                ) { Text("Save") }
            }
        }
    }
}
