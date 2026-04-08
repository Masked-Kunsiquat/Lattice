package com.github.maskedkunisquat.lattice.ui

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.maskedkunisquat.lattice.core.logic.CircumplexMapper
import com.github.maskedkunisquat.lattice.core.logic.MoodLabel

private data class MoodState(
    val position: Offset,
    val label: MoodLabel,
    val valence: Float,
    val arousal: Float,
)

@Composable
fun MoodGrid(
    modifier: Modifier = Modifier,
    onMoodChanged: (valence: Float, arousal: Float, label: MoodLabel) -> Unit = { _, _, _ -> },
) {
    var moodState by remember { mutableStateOf<MoodState?>(null) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "High Energy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Unpleasant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.rotate(-90f),
            )

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            handlePosition(down.position, size.width.toFloat(), size.height.toFloat()) { state ->
                                moodState = state
                                onMoodChanged(state.valence, state.arousal, state.label)
                            }
                            down.consume()
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.pressed) {
                                    change.consume()
                                    handlePosition(change.position, size.width.toFloat(), size.height.toFloat()) { state ->
                                        moodState = state
                                        onMoodChanged(state.valence, state.arousal, state.label)
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f

                // Quadrant backgrounds (subtle tint per emotional region)
                drawRect(Color(0xFFFFB300).copy(alpha = 0.08f), topLeft = Offset(cx, 0f),  size = Size(cx, cy)) // excited  – amber
                drawRect(Color(0xFF4CAF50).copy(alpha = 0.08f), topLeft = Offset(cx, cy),  size = Size(cx, cy)) // calm     – green
                drawRect(Color(0xFF607D8B).copy(alpha = 0.08f), topLeft = Offset(0f,  cy), size = Size(cx, cy)) // fatigued – blue-grey
                drawRect(Color(0xFFF44336).copy(alpha = 0.08f), topLeft = Offset(0f,  0f),  size = Size(cx, cy)) // tense   – red

                // Axis lines
                val axisColor = Color.White.copy(alpha = 0.15f)
                val strokePx = 1.dp.toPx()
                drawLine(axisColor, Offset(0f, cy), Offset(w, cy), strokePx)
                drawLine(axisColor, Offset(cx, 0f), Offset(cx, h), strokePx)

                // Glowing selector dot
                moodState?.position?.let { pos ->
                    val dotColor = Color.White
                    val glowRadius = 24.dp.toPx()
                    val dotRadius = 8.dp.toPx()

                    drawIntoCanvas { canvas ->
                        val glowPaint = Paint().asFrameworkPaint().apply {
                            isAntiAlias = true
                            color = dotColor.toArgb()
                            maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawCircle(pos.x, pos.y, dotRadius * 2f, glowPaint)
                    }
                    drawCircle(color = dotColor, radius = dotRadius, center = pos)
                }
            }

            Text(
                text = "Pleasant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.rotate(90f),
            )
        }

        Text(
            text = "Low Energy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = moodState?.label?.name ?: "Tap to select your mood",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (moodState != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

private fun handlePosition(
    rawOffset: Offset,
    width: Float,
    height: Float,
    onResult: (MoodState) -> Unit,
) {
    if (width <= 0f || height <= 0f) return
    val clamped = Offset(rawOffset.x.coerceIn(0f, width), rawOffset.y.coerceIn(0f, height))
    val valence = (clamped.x / width) * 2f - 1f          // left=-1, right=+1
    val arousal = 1f - (clamped.y / height) * 2f          // top=+1,  bottom=-1
    val label = CircumplexMapper.getLabel(valence, arousal)
    onResult(MoodState(clamped, label, valence, arousal))
}
