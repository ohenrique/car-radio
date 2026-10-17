package com.ohenrique.carradio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohenrique.carradio.BuildConfig
import com.ohenrique.carradio.data.RadioSettings
import com.ohenrique.carradio.data.SteeringWheelAction
import com.ohenrique.carradio.data.TunerBackend
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.Sensitivity

@Composable
fun SettingsScreen(ui: RadioUiState, onBack: () -> Unit, onChange: ((RadioSettings) -> RadioSettings) -> Unit) {
    val settings = ui.settings
    Column(
        Modifier
            .fillMaxSize()
            .background(RadioColors.Background)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(RadioIcons.ChevronLeft, contentDescription = "Voltar", tint = RadioColors.Text, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Ajustes", color = RadioColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 56.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Setting(
                "Sintonizador",
                when (settings.backend) {
                    TunerBackend.QN8035 -> "Controla o chip QN8035 direto pelo barramento I2C. Precisa de root e das ferramentas i2cget/i2cset."
                    TunerBackend.SIMULATOR -> "Estações de mentira, para testar o app fora do carro."
                },
            ) {
                Choices(
                    listOf(TunerBackend.QN8035 to "QN8035 (I2C)", TunerBackend.SIMULATOR to "Simulador"),
                    settings.backend,
                ) { value -> onChange { it.copy(backend = value) } }
            }

            if (settings.backend == TunerBackend.QN8035) {
                Setting("Barramento I2C", "Automático procura o QN8035 em todos os /dev/i2c-*.") {
                    Stepper(
                        label = settings.i2cBus?.let { "i2c-$it" } ?: "Automático",
                        onMinus = { onChange { it.copy(i2cBus = it.i2cBus?.let { bus -> (bus - 1).takeIf { b -> b >= 0 } }) } },
                        onPlus = { onChange { it.copy(i2cBus = (it.i2cBus ?: -1) + 1) } },
                    )
                }
                Setting("Endereço I2C", "O QN8035 responde em 0x10.") {
                    Stepper(
                        label = "0x%02x".format(settings.i2cAddress),
                        onMinus = { onChange { it.copy(i2cAddress = (it.i2cAddress - 1).coerceAtLeast(0x03)) } },
                        onPlus = { onChange { it.copy(i2cAddress = (it.i2cAddress + 1).coerceAtMost(0x77)) } },
                    )
                }
                Setting("Clock de referência", "Frequência do cristal ligado ao QN8035 na placa.") {
                    Choices(
                        listOf(32_768 to "32,768 kHz", 12_000_000 to "12 MHz", 24_000_000 to "24 MHz", 26_000_000 to "26 MHz"),
                        settings.clockHz,
                    ) { value -> onChange { it.copy(clockHz = value) } }
                }
            }

            Setting("Região", "Brasil inclui a faixa estendida de 76,1 a 87,9 MHz.") {
                Choices(
                    listOf(
                        Region.BRAZIL to "Brasil",
                        Region.AMERICAS to "Américas",
                        Region.USA to "EUA",
                        Region.EUROPE to "Europa",
                        Region.JAPAN to "Japão",
                    ),
                    settings.region,
                ) { value -> onChange { it.copy(region = value) } }
            }
            Setting("Sensibilidade da busca", "LOC para só nas rádios fortes; DX também pega as distantes.") {
                Choices(listOf(Sensitivity.LOCAL to "LOC", Sensitivity.DX to "DX"), settings.sensitivity) { value ->
                    onChange { it.copy(sensitivity = value) }
                }
            }
            Setting("Áudio", "Mono reduz o chiado de rádios fracas.") {
                Choices(listOf(false to "Estéreo automático", true to "Mono"), settings.forceMono) { value ->
                    onChange { it.copy(forceMono = value) }
                }
            }
            Setting("RDS", "Mostra o nome da rádio e o texto que ela transmite.") {
                Choices(listOf(true to "Ligado", false to "Desligado"), settings.rdsEnabled) { value ->
                    onChange { it.copy(rdsEnabled = value) }
                }
            }
            Setting("Botões do volante", "O que fazem os botões de próxima e anterior.") {
                Choices(
                    listOf(SteeringWheelAction.PRESETS to "Trocar memória", SteeringWheelAction.SEEK to "Buscar estação"),
                    settings.steeringWheel,
                ) { value -> onChange { it.copy(steeringWheel = value) } }
            }

            val status = when (ui.tuner.power) {
                PowerState.ON -> "ligado"
                PowerState.STARTING -> "ligando"
                PowerState.OFF -> "desligado"
                PowerState.ERROR -> "erro: ${ui.tuner.error}"
            }
            Text(
                "${ui.tunerName}: $status\nVersão ${BuildConfig.VERSION_NAME}",
                color = RadioColors.TextFaint,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Setting(title: String, description: String, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp)) {
        Text(title, color = RadioColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(description, color = RadioColors.TextDim, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) RadioColors.Accent else RadioColors.Surface)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(
                    label,
                    color = if (active) RadioColors.Background else RadioColors.Text,
                    fontSize = 16.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton("−", onMinus)
        Text(
            label,
            color = RadioColors.Text,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 120.dp).padding(horizontal = 12.dp),
        )
        StepperButton("+", onPlus)
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RadioColors.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = RadioColors.Text, fontSize = 22.sp)
    }
}
