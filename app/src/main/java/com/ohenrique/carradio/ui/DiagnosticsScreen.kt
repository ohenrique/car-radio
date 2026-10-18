package com.ohenrique.carradio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ohenrique.carradio.CarRadioApp
import com.ohenrique.carradio.diag.Diagnostics
import kotlinx.coroutines.launch

/** Shows what identifies the head unit, so the user can photograph it or send it as text. */
@Composable
fun DiagnosticsScreen(ui: RadioUiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var collecting by remember { mutableStateOf(false) }

    fun refresh() {
        if (collecting) return
        collecting = true
        scope.launch {
            val app = context.applicationContext as CarRadioApp
            report = runCatching { Diagnostics.collect(app, ui.tunerName, ui.tuner, app.radioLog.lines()) }
                .getOrElse { "erro ao coletar: ${it.message ?: it}" }
            collecting = false
        }
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico do Rádio")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(Intent.createChooser(send, "Enviar diagnóstico")) }
    }

    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Diagnóstico do Rádio", text))
    }

    LaunchedEffect(Unit) { refresh() }

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
            Text("Diagnóstico", color = RadioColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = ::refresh, enabled = !collecting) { Text("Atualizar") }
            report?.let { text ->
                TextButton(onClick = { copy(text) }) { Text("Copiar") }
                TextButton(onClick = { share(text) }) { Text("Compartilhar") }
            }
        }
        Text(
            "Mostra como esta central é por dentro, para descobrir como o rádio dela funciona. " +
                "Nada é alterado. Tire fotos da tela ou toque em Compartilhar e mande o texto.",
            color = RadioColors.TextDim,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 56.dp, top = 4.dp, bottom = 12.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 56.dp, bottom = 24.dp),
        ) {
            val text = report
            when {
                text == null -> Text("Coletando…", color = RadioColors.TextDim, fontSize = 16.sp)
                else -> SelectionContainer {
                    Text(text, color = RadioColors.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                }
            }
        }
        if (collecting && report != null) {
            Spacer(Modifier.height(4.dp))
            Text("Atualizando…", color = RadioColors.TextFaint, fontSize = 13.sp)
        }
    }
}
