package com.github.maskedkunisquat.lattice.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

/**
 * Modal bottom sheet that displays the reframe pipeline state.
 *
 * States:
 * - [ReframeState.Loading]   — Skeleton shimmer placeholder while Stages 1+2 run.
 * - [ReframeState.Streaming] — Token-by-token text appended as Stage 3 generates.
 * - [ReframeState.Done]      — Full reframe with Apply / Dismiss actions.
 * - [ReframeState.Error]     — Error message with a Dismiss action.
 * - [ReframeState.Idle]      — Sheet is never shown in this state.
 *
 * [onApply] persists the reframe to the saved entry (calls [JournalEditorViewModel.applyReframe]).
 * [onDismiss] resets state to Idle without writing to the DB.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReframeBottomSheet(
    reframeState: ReframeState,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Reframe",
                style = MaterialTheme.typography.titleMedium,
            )

            when (reframeState) {
                ReframeState.Loading          -> ShimmerContent()
                is ReframeState.Streaming     -> StreamingContent(reframeState.partial)
                is ReframeState.Done          -> DoneContent(reframeState.text, onApply, onDismiss)
                is ReframeState.Error         -> ErrorContent(reframeState.msg, onDismiss)
                ReframeState.Idle             -> Unit
            }
        }
    }
}

// ── Loading shimmer ───────────────────────────────────────────────────────────

@Composable
private fun ShimmerContent() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offsetX by transition.animateFloat(
        initialValue = -600f,
        targetValue  = 600f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerOffset",
    )
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        MaterialTheme.colorScheme.surfaceVariant,
    )
    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(offsetX, 0f),
        end    = Offset(offsetX + 600f, 0f),
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            val fraction = if (i == 2) 0.6f else 1f
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
    }
}

// ── Streaming ─────────────────────────────────────────────────────────────────

@Composable
private fun StreamingContent(partial: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = partial,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Done ──────────────────────────────────────────────────────────────────────

@Composable
private fun DoneContent(text: String, onApply: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onApply) { Text("Apply") }
    }
}

// ── Error ─────────────────────────────────────────────────────────────────────

@Composable
private fun ErrorContent(msg: String, onDismiss: () -> Unit) {
    Text(
        text = msg,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text("Dismiss")
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Shimmer – Loading", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewLoading() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        ReframeBottomSheet(
            reframeState = ReframeState.Loading,
            onApply = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Streaming", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewStreaming() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        ReframeBottomSheet(
            reframeState = ReframeState.Streaming("What evidence do you have that this will go wrong?"),
            onApply = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Done", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewDone() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        ReframeBottomSheet(
            reframeState = ReframeState.Done(
                "You've navigated uncertain situations before and found your footing. " +
                "What's one small piece of evidence that contradicts the feeling that this is hopeless?"
            ),
            onApply = {},
            onDismiss = {},
        )
    }
}
