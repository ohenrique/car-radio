@file:OptIn(ExperimentalFoundationApi::class)

package com.ohenrique.carradio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohenrique.carradio.data.PRESETS_PER_PAGE
import com.ohenrique.carradio.data.PRESET_PAGES
import com.ohenrique.carradio.data.Preset
import com.ohenrique.carradio.tuner.formatMhz

private val TileShape = RoundedCornerShape(14.dp)

/**
 * FM1/FM2/FM3 pages of six presets, like a stock head unit. Tap a saved preset to tune, tap an
 * empty one to save the current station there, long-press for save or delete.
 */
@Composable
fun PresetBar(
    presets: List<Preset?>,
    page: Int,
    currentKhz: Int,
    columns: Int,
    tileHeight: Dp,
    onPageChange: (Int) -> Unit,
    onTap: (Int) -> Unit,
    onSave: (Int) -> Unit,
    onClear: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = PRESETS_PER_PAGE / columns
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageButton(page, onPageChange, Modifier.width(72.dp).height(tileHeight * rows + 8.dp * (rows - 1)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0 until columns) {
                        val slot = row * columns + column
                        PresetTile(
                            number = page * PRESETS_PER_PAGE + slot + 1,
                            preset = presets.getOrNull(slot),
                            active = presets.getOrNull(slot)?.frequencyKhz == currentKhz,
                            currentKhz = currentKhz,
                            onTap = { onTap(slot) },
                            onSave = { onSave(slot) },
                            onClear = { onClear(slot) },
                            modifier = Modifier.weight(1f).height(tileHeight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageButton(page: Int, onPageChange: (Int) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .clip(TileShape)
            .background(RadioColors.Surface)
            .combinedClickable(onClick = { onPageChange((page + 1) % PRESET_PAGES) }),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FM${page + 1}", color = RadioColors.Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(PRESET_PAGES) { i ->
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (i == page) RadioColors.Accent else RadioColors.TextFaint),
                )
            }
        }
    }
}

@Composable
private fun PresetTile(
    number: Int,
    preset: Preset?,
    active: Boolean,
    currentKhz: Int,
    onTap: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .clip(TileShape)
                .background(if (active) RadioColors.SurfaceHigh else RadioColors.Surface)
                .then(if (active) Modifier.border(2.dp, RadioColors.Accent, TileShape) else Modifier)
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (preset == null) {
                Text("$number", color = RadioColors.TextFaint, fontSize = 18.sp)
            } else {
                Text(
                    formatMhz(preset.frequencyKhz),
                    color = if (active) RadioColors.Accent else RadioColors.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                preset.name?.let {
                    Text(it, color = RadioColors.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Salvar ${formatMhz(currentKhz)} na memória $number") },
                onClick = {
                    menuOpen = false
                    onSave()
                },
            )
            if (preset != null) {
                DropdownMenuItem(
                    text = { Text("Apagar memória $number") },
                    onClick = {
                        menuOpen = false
                        onClear()
                    },
                )
            }
        }
    }
}
