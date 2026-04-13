package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

/**
 * A 2-D circumplex touch target for capturing user valence and arousal after a reframe.
 *
 * Displays a square grid with labelled axes:
 *   - X axis → arousal  (left = calm, right = activated)
 *   - Y axis → valence  (bottom = unpleasant, top = pleasant)
 *
 * The user drags the dot to position it, then taps "Confirm" to emit the normalised
 * coordinates in [-1, 1]. "Skip" dismisses the grid without emitting coordinates.
 *
 * @param onConfirm Called with (valence, arousal) in [-1, 1] when the user confirms.
 * @param onSkip    Called when the user skips without providing coordinates.
 */
@Composable
fun CircumplexGrid(
    onConfirm: (valence: Float, arousal: Float) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dot position in canvas pixels; (0,0) = center of the grid.
    var dotOffset by remember { mutableStateOf(Offset.Zero) }
    // Canvas size in pixels — set during first layout pass.
    var canvasSize by remember { mutableStateOf(0f) }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val dotColor = MaterialTheme.colorScheme.primary
    val dotRadius = 14.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "How does this land?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Drag the dot to where this feels right.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Axis labels — top row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text = "pleasant ↑",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Grid + side labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "calm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
            ) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val half = canvasSize / 2f
                                dotOffset = Offset(
                                    x = (dotOffset.x + dragAmount.x).coerceIn(-half, half),
                                    y = (dotOffset.y + dragAmount.y).coerceIn(-half, half),
                                )
                            }
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown || canvasSize == 0f) return@onKeyEvent false
                            val step = canvasSize * 0.1f
                            val half = canvasSize / 2f
                            when (event.key) {
                                Key.DirectionRight -> { dotOffset = dotOffset.copy(x = (dotOffset.x + step).coerceIn(-half, half)); true }
                                Key.DirectionLeft  -> { dotOffset = dotOffset.copy(x = (dotOffset.x - step).coerceIn(-half, half)); true }
                                Key.DirectionDown  -> { dotOffset = dotOffset.copy(y = (dotOffset.y + step).coerceIn(-half, half)); true }
                                Key.DirectionUp    -> { dotOffset = dotOffset.copy(y = (dotOffset.y - step).coerceIn(-half, half)); true }
                                else               -> false
                            }
                        }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Increase valence") {
                                    if (canvasSize > 0f) {
                                        val half = canvasSize / 2f
                                        dotOffset = dotOffset.copy(y = (dotOffset.y - canvasSize * 0.1f).coerceIn(-half, half))
                                    }
                                    true
                                },
                                CustomAccessibilityAction("Decrease valence") {
                                    if (canvasSize > 0f) {
                                        val half = canvasSize / 2f
                                        dotOffset = dotOffset.copy(y = (dotOffset.y + canvasSize * 0.1f).coerceIn(-half, half))
                                    }
                                    true
                                },
                                CustomAccessibilityAction("Increase arousal") {
                                    if (canvasSize > 0f) {
                                        val half = canvasSize / 2f
                                        dotOffset = dotOffset.copy(x = (dotOffset.x + canvasSize * 0.1f).coerceIn(-half, half))
                                    }
                                    true
                                },
                                CustomAccessibilityAction("Decrease arousal") {
                                    if (canvasSize > 0f) {
                                        val half = canvasSize / 2f
                                        dotOffset = dotOffset.copy(x = (dotOffset.x - canvasSize * 0.1f).coerceIn(-half, half))
                                    }
                                    true
                                },
                            )
                        }
                        .focusable(),
                ) {
                    canvasSize = size.minDimension
                    val half = size.minDimension / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    // Background quadrant lines
                    val strokeThin = Stroke(width = 1.dp.toPx())
                    val strokeAxis = Stroke(width = 1.5f.dp.toPx())

                    // Quadrant grid — faint lines at ±0.5 normalised
                    for (step in listOf(-0.5f, 0.5f)) {
                        val px = center.x + step * half
                        val py = center.y + step * half
                        drawLine(gridColor, Offset(px, 0f), Offset(px, size.height), strokeThin.width)
                        drawLine(gridColor, Offset(0f, py), Offset(size.width, py), strokeThin.width)
                    }

                    // Axes
                    drawLine(axisColor, Offset(center.x, 0f), Offset(center.x, size.height), strokeAxis.width)
                    drawLine(axisColor, Offset(0f, center.y), Offset(size.width, center.y), strokeAxis.width)

                    // Border
                    drawRect(axisColor, style = strokeThin)

                    // Dot — clamped to grid bounds
                    drawCircle(
                        color = dotColor,
                        radius = dotRadius.toPx(),
                        center = center + dotOffset,
                    )
                }
            }

            Text(
                text = "activated",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Bottom axis label
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text = "↓ unpleasant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSkip) { Text("Skip") }
            OutlinedButton(
                onClick = {
                    if (canvasSize > 0f) {
                        val half = canvasSize / 2f
                        // X → arousal: right = +1; Y → valence: up = +1 (screen Y is inverted)
                        val arousal = (dotOffset.x / half).coerceIn(-1f, 1f)
                        val valence = (-dotOffset.y / half).coerceIn(-1f, 1f)
                        onConfirm(valence, arousal)
                    }
                },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Confirm")
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "CircumplexGrid (dark)", showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun PreviewCircumplexGrid() {
    LatticeTheme(darkTheme = true, dynamicColor = false) {
        CircumplexGrid(
            onConfirm = { _, _ -> },
            onSkip = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
