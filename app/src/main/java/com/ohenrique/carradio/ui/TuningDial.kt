package com.ohenrique.carradio.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohenrique.carradio.tuner.Region
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Visible width of the dial, in kHz. */
private const val SPAN_KHZ = 8_000f

/**
 * A ruler that slides under a fixed needle. Drag it to tune, or tap a spot to jump there;
 * the tuner follows every channel the needle crosses.
 */
@Composable
fun TuningDial(
    frequencyKhz: Int,
    region: Region,
    onTune: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val position = remember { Animatable(frequencyKhz.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val currentFrequency by rememberUpdatedState(frequencyKhz)
    val currentOnTune by rememberUpdatedState(onTune)

    LaunchedEffect(frequencyKhz, dragging) {
        if (!dragging) position.animateTo(frequencyKhz.toFloat(), tween(durationMillis = 300))
    }

    val labelStyle = TextStyle(color = RadioColors.TextDim, fontSize = 13.sp)

    Canvas(
        modifier
            .pointerInput(region) {
                var dragKhz = 0f
                var lastSent = 0
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragKhz = position.value
                        lastSent = currentFrequency
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        dragKhz = (dragKhz - dx * SPAN_KHZ / size.width)
                            .coerceIn(region.minKhz.toFloat(), region.maxKhz.toFloat())
                        scope.launch { position.snapTo(dragKhz) }
                        val snapped = region.snap(dragKhz.roundToInt())
                        if (snapped != lastSent) {
                            lastSent = snapped
                            currentOnTune(snapped)
                        }
                    },
                )
            }
            .pointerInput(region) {
                detectTapGestures { offset ->
                    val khz = position.value + (offset.x - size.width / 2f) * SPAN_KHZ / size.width
                    currentOnTune(region.snap(khz.roundToInt()))
                }
            },
    ) {
        val center = position.value
        val pxPerKhz = size.width / SPAN_KHZ
        val first = (floor((center - SPAN_KHZ / 2) / 100f) * 100f).toInt().coerceAtLeast(region.minKhz / 100 * 100)
        val last = (ceil((center + SPAN_KHZ / 2) / 100f) * 100f).toInt().coerceAtMost(region.maxKhz)
        val tickBottom = size.height
        val labelHeight = 18.dp.toPx()

        for (khz in first..last step 100) {
            if (khz < region.minKhz) continue
            val x = size.width / 2f + (khz - center) * pxPerKhz
            val (length, color) = when {
                khz % 1000 == 0 -> 24.dp.toPx() to RadioColors.TextDim
                khz % 500 == 0 -> 16.dp.toPx() to RadioColors.TextFaint
                else -> 9.dp.toPx() to RadioColors.TextFaint
            }
            drawLine(color, Offset(x, tickBottom - length), Offset(x, tickBottom), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            if (khz % 2000 == 0) {
                val layout = textMeasurer.measure((khz / 1000).toString(), labelStyle)
                drawText(layout, topLeft = Offset(x - layout.size.width / 2f, (labelHeight - layout.size.height) / 2f))
            }
        }

        fadeEdges()
        drawLine(
            RadioColors.Accent,
            Offset(size.width / 2f, labelHeight),
            Offset(size.width / 2f, size.height),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.fadeEdges() {
    val fade = 56.dp.toPx()
    drawRect(
        Brush.horizontalGradient(listOf(RadioColors.Background, Color.Transparent), startX = 0f, endX = fade),
        size = size.copy(width = fade),
    )
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, RadioColors.Background), startX = size.width - fade, endX = size.width),
        topLeft = Offset(size.width - fade, 0f),
        size = size.copy(width = fade),
    )
}
