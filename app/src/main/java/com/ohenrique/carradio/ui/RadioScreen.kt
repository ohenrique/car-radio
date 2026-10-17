@file:OptIn(ExperimentalFoundationApi::class)

package com.ohenrique.carradio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ohenrique.carradio.data.TunerBackend
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Sensitivity
import com.ohenrique.carradio.tuner.TunerActivity
import com.ohenrique.carradio.tuner.formatMhz
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RadioScreen(viewModel: RadioViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    if (ui.showSettings) {
        BackHandler { viewModel.showSettings(false) }
        SettingsScreen(ui, onBack = { viewModel.showSettings(false) }, onChange = viewModel::updateSettings)
    } else {
        RadioMain(ui, viewModel)
    }
}

@Composable
private fun RadioMain(ui: RadioUiState, viewModel: RadioViewModel) {
    val tuner = ui.tuner
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(RadioColors.Background)
            .systemBarsPadding(),
    ) {
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 420.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = if (compact) 6.dp else 12.dp)) {
            TopBar(
                ui = ui,
                onToggleSensitivity = viewModel::toggleSensitivity,
                onScan = viewModel::scan,
                onToggleMute = viewModel::toggleMute,
                onSettings = { viewModel.showSettings(true) },
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (tuner.power == PowerState.ERROR) {
                    ErrorPanel(
                        message = tuner.error,
                        onRetry = viewModel::retry,
                        onSettings = { viewModel.showSettings(true) },
                        onUseSimulator = { viewModel.updateSettings { it.copy(backend = TunerBackend.SIMULATOR) } },
                    )
                } else {
                    FrequencyPanel(ui, viewModel, seekButtonSize = if (landscape) 88.dp else 64.dp)
                }
            }
            TuningDial(
                frequencyKhz = tuner.frequencyKhz,
                region = tuner.region,
                onTune = viewModel::tune,
                modifier = Modifier.fillMaxWidth().height(if (compact) 52.dp else 64.dp),
            )
            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
            PresetBar(
                presets = ui.pagePresets,
                page = ui.presetPage,
                currentKhz = tuner.frequencyKhz,
                columns = if (landscape) 6 else 3,
                tileHeight = if (compact) 60.dp else 72.dp,
                onPageChange = viewModel::selectPage,
                onTap = viewModel::onPresetTap,
                onSave = viewModel::onPresetLongPress,
                onClear = viewModel::clearPreset,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(ui.scanning, enter = fadeIn(), exit = fadeOut()) {
            ScanOverlay(frequencyKhz = tuner.frequencyKhz, onStop = viewModel::cancelScan)
        }
    }
}

@Composable
private fun TopBar(
    ui: RadioUiState,
    onToggleSensitivity: () -> Unit,
    onScan: () -> Unit,
    onToggleMute: () -> Unit,
    onSettings: () -> Unit,
) {
    val tuner = ui.tuner
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("FM", color = RadioColors.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(18.dp))
        Indicator("ST", tuner.signal.stereo && tuner.power == PowerState.ON)
        Spacer(Modifier.width(12.dp))
        Indicator("RDS", tuner.rds.programService != null)
        // FYT units do not report signal strength.
        if (ui.settings.backend != TunerBackend.FYT) {
            Spacer(Modifier.width(14.dp))
            SignalBars(if (tuner.power == PowerState.ON) tuner.signal.bars else 0)
        }
        if (ui.settings.backend == TunerBackend.SIMULATOR) {
            Spacer(Modifier.width(14.dp))
            Text("SIMULADOR", color = RadioColors.TextFaint, fontSize = 12.sp, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggleSensitivity)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                if (tuner.sensitivity == Sensitivity.LOCAL) "LOC" else "DX",
                color = RadioColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
            )
        }
        BarIcon(RadioIcons.Scan, "Buscar estações", onScan)
        BarIcon(if (tuner.muted) RadioIcons.VolumeOff else RadioIcons.VolumeUp, if (tuner.muted) "Ouvir" else "Silenciar", onToggleMute)
        BarIcon(Icons.Filled.Settings, "Ajustes", onSettings)
    }
}

@Composable
private fun Indicator(label: String, on: Boolean) {
    Text(
        label,
        color = if (on) RadioColors.Text else RadioColors.TextFaint,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun SignalBars(bars: Int) {
    Canvas(Modifier.size(width = 22.dp, height = 14.dp)) {
        val barWidth = size.width / 7f
        for (i in 0 until 4) {
            val height = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (i < bars) RadioColors.Text else RadioColors.TextFaint,
                topLeft = Offset(i * barWidth * 2, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 3),
            )
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = RadioColors.Text, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun FrequencyPanel(ui: RadioUiState, viewModel: RadioViewModel, seekButtonSize: Dp) {
    val tuner = ui.tuner
    val idle = tuner.power == PowerState.ON
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(RadioIcons.SkipPrevious, "Buscar para trás", seekButtonSize) { viewModel.seek(SeekDirection.DOWN) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            val digits = with(LocalDensity.current) { (maxHeight * 0.46f).coerceAtMost(maxWidth * 0.2f).toSp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RepeatButton(RadioIcons.ChevronLeft, "Descer 1 canal") { viewModel.step(-1) }
                    Text(
                        formatMhz(tuner.frequencyKhz),
                        modifier = Modifier.alpha(if (idle) 1f else 0.4f).padding(horizontal = 8.dp),
                        style = TextStyle(
                            color = RadioColors.Text,
                            fontSize = digits,
                            fontWeight = FontWeight.Light,
                            fontFeatureSettings = "tnum",
                        ),
                        maxLines = 1,
                    )
                    RepeatButton(RadioIcons.ChevronRight, "Subir 1 canal") { viewModel.step(+1) }
                }
                Text(
                    text = when {
                        tuner.power == PowerState.STARTING -> "Ligando o sintonizador…"
                        tuner.activity == TunerActivity.SEEKING -> "Buscando…"
                        else -> tuner.rds.programService ?: "MHz"
                    },
                    color = if (tuner.rds.programService != null && tuner.activity == TunerActivity.IDLE) RadioColors.Accent else RadioColors.TextDim,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tuner.rds.radioText.orEmpty(),
                    color = RadioColors.TextDim,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.85f).basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }
        RoundButton(RadioIcons.SkipNext, "Buscar para frente", seekButtonSize) { viewModel.seek(SeekDirection.UP) }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(RadioColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = RadioColors.Text, modifier = Modifier.size(size * 0.45f))
    }
}

/** Steps once on tap and keeps stepping while held. */
@Composable
private fun RepeatButton(icon: ImageVector, description: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentOnStep by rememberUpdatedState(onStep)
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (pressed) RadioColors.SurfaceHigh else RadioColors.Background)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentOnStep()
                        val repeat = scope.launch {
                            delay(450)
                            while (true) {
                                currentOnStep()
                                delay(110)
                            }
                        }
                        tryAwaitRelease()
                        repeat.cancel()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = RadioColors.TextDim, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun ErrorPanel(message: String?, onRetry: () -> Unit, onSettings: () -> Unit, onUseSimulator: () -> Unit) {
    Column(Modifier.fillMaxWidth(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Sintonizador indisponível", color = RadioColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Text(
            message ?: "Erro desconhecido",
            color = RadioColors.TextDim,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text("Tentar de novo") }
            TextButton(onClick = onSettings) { Text("Ajustes") }
            TextButton(onClick = onUseSimulator) { Text("Usar simulador") }
        }
    }
}

@Composable
private fun ScanOverlay(frequencyKhz: Int, onStop: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(RadioColors.Background.copy(alpha = 0.92f))
            // Swallow touches so nothing underneath reacts during the scan.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Buscando estações", color = RadioColors.TextDim, fontSize = 20.sp)
            Text(
                formatMhz(frequencyKhz),
                style = TextStyle(color = RadioColors.Text, fontSize = 64.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
            )
            Text("As memórias FM1 a FM3 serão substituídas", color = RadioColors.TextFaint, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onStop) { Text("Parar", fontSize = 18.sp) }
        }
    }
}
