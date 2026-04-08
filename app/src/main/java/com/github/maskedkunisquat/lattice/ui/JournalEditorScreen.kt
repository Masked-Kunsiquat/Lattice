package com.github.maskedkunisquat.lattice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel
import com.github.maskedkunisquat.lattice.core.logic.PrivacyLevel
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

private val LocalBlue = Color(0xFF1976D2)
private val CloudAmber = Color(0xFFFF8F00)

// Public entry point — collects ViewModel state and delegates to JournalEditorContent.
@Composable
fun JournalEditorScreen(
    viewModel: JournalEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val privacyState by viewModel.privacyState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    JournalEditorContent(
        privacyState = privacyState,
        uiState = uiState,
        onTextChanged = viewModel::onTextChanged,
        onMoodChanged = viewModel::onMoodChanged,
        onSave = viewModel::save,
        onResetSaved = viewModel::resetSaved,
        onDismissReframe = viewModel::dismissReframe,
        modifier = modifier,
    )
}

// Stateless inner composable — all mutable state lives in the ViewModel.
// Previews target this directly with stub state.
@Composable
private fun JournalEditorContent(
    privacyState: PrivacyLevel,
    uiState: EditorUiState,
    onTextChanged: (String) -> Unit,
    onMoodChanged: (Float, Float, MoodLabel) -> Unit,
    onSave: () -> Unit,
    onResetSaved: () -> Unit,
    onDismissReframe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor by animateColorAsState(
        targetValue = when (privacyState) {
            is PrivacyLevel.LocalOnly -> LocalBlue
            is PrivacyLevel.CloudTransit -> CloudAmber
        },
        animationSpec = tween(durationMillis = 600),
        label = "privacyBorder",
    )

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onResetSaved()
    }

    val privacyLabel = when (val ps = privacyState) {
        is PrivacyLevel.LocalOnly -> "Processing Locally"
        is PrivacyLevel.CloudTransit -> "Cloud: ${ps.providerName}"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Privacy status pill
        Surface(
            color = borderColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = privacyLabel,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = borderColor,
            )
        }

        // Mood grid
        MoodGrid(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f),
            onMoodChanged = onMoodChanged,
        )

        // Privacy-bordered journal text field
        OutlinedTextField(
            value = uiState.text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f),
            placeholder = { Text("What's on your mind?") },
            visualTransformation = PiiHighlightTransformation(
                highlightColor = MaterialTheme.colorScheme.tertiary,
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor,
                unfocusedBorderColor = borderColor.copy(alpha = 0.5f),
            ),
        )

        // Reframe: loading indicator
        if (uiState.isReframing) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        // Reframe: result card
        uiState.reframeResult?.let { reframe ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Reframe",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        IconButton(onClick = onDismissReframe) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss reframe",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    Text(
                        text = reframe,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        }

        // Reframe: error
        uiState.error?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState.text.isNotBlank() && uiState.moodSelected && !uiState.isReframing,
        ) {
            Text("Save Entry")
        }
    }
}

@Preview(name = "Editor – Local (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorLocalPreview() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.LocalOnly,
            uiState = EditorUiState(text = "Today I spoke with [PERSON_00000000-0000-0000-0000-000000000001] about the project."),
            onTextChanged = {},
            onMoodChanged = { _, _, _ -> },
            onSave = {},
            onResetSaved = {},
            onDismissReframe = {},
        )
    }
}

@Preview(name = "Editor – Cloud transit (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorCloudPreview() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.CloudTransit(
                providerName = "cloud_claude",
                sinceTimestamp = 0L,
            ),
            uiState = EditorUiState(text = "Feeling anxious about the presentation.", moodSelected = true),
            onTextChanged = {},
            onMoodChanged = { _, _, _ -> },
            onSave = {},
            onResetSaved = {},
            onDismissReframe = {},
        )
    }
}
