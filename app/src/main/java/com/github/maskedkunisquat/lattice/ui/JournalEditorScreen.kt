package com.github.maskedkunisquat.lattice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

// Public entry point — pulls state from the ViewModel and delegates to JournalEditorContent.
@Composable
fun JournalEditorScreen(
    viewModel: JournalEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val privacyState by viewModel.privacyState.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    JournalEditorContent(
        privacyState = privacyState,
        saved = saved,
        onSave = viewModel::save,
        onResetSaved = viewModel::resetSaved,
        modifier = modifier,
    )
}

// Stateless inner composable — previews target this directly.
@Composable
private fun JournalEditorContent(
    privacyState: PrivacyLevel,
    saved: Boolean,
    onSave: (content: String, valence: Float, arousal: Float, label: MoodLabel) -> Unit,
    onResetSaved: () -> Unit,
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

    var text by remember { mutableStateOf("") }
    var valence by remember { mutableFloatStateOf(0f) }
    var arousal by remember { mutableFloatStateOf(0f) }
    var currentLabel by remember { mutableStateOf(MoodLabel.ALIVE) }
    var moodSelected by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            text = ""
            moodSelected = false
            onResetSaved()
        }
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
            onMoodChanged = { v, a, l ->
                valence = v
                arousal = a
                currentLabel = l
                moodSelected = true
            },
        )

        // Privacy-bordered journal text field
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
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

        Button(
            onClick = { onSave(text, valence, arousal, currentLabel) },
            modifier = Modifier.fillMaxWidth(),
            enabled = text.isNotBlank() && moodSelected,
        ) {
            Text("Save Entry")
        }
    }
}

@Preview(name = "Editor – Local (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorLocalPreview() {
    LatticeTheme(dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.LocalOnly,
            saved = false,
            onSave = { _, _, _, _ -> },
            onResetSaved = {},
        )
    }
}

@Preview(name = "Editor – Cloud transit (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun EditorCloudPreview() {
    LatticeTheme(dynamicColor = false) {
        JournalEditorContent(
            privacyState = PrivacyLevel.CloudTransit(
                providerName = "cloud_claude",
                sinceTimestamp = 0L,
            ),
            saved = false,
            onSave = { _, _, _, _ -> },
            onResetSaved = {},
        )
    }
}
