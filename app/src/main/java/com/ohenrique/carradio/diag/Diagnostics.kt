package com.ohenrique.carradio.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.ohenrique.carradio.BuildConfig
import com.ohenrique.carradio.fyt.SyuToolkitClient
import com.ohenrique.carradio.tuner.TunerState
import com.ohenrique.carradio.tuner.fyt.Syu
import com.ohenrique.carradio.tuner.fyt.SyuUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Collects what identifies the head unit's platform and how its radio is reached, as plain text
 * the user can photograph or share: system properties, vendor packages and their services, known
 * framework classes, Binder services, device nodes, passive checks of com.nwd.radio.service and
 * com.syu.ms, and what the tuner traced in real use ([radioLog]).
 *
 * Nothing here changes the head unit: no command is sent to any service.
 */
object Diagnostics {

    suspend fun collect(
        context: Context,
        tunerName: String,
        tuner: TunerState,
        radioLog: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        fun section(title: String, block: () -> List<String>) {
            report.append("== ").append(title).append(" ==\n")
            val lines = runCatching(block).getOrElse { listOf("erro: ${it.message ?: it}") }
            if (lines.isEmpty()) report.append("(nada)\n")
            lines.forEach { report.append(it).append('\n') }
            report.append('\n')
        }

        section("App") {
            listOf(
                "versão ${BuildConfig.VERSION_NAME}",
                "sintonizador: $tunerName, ${tuner.power}${tuner.error?.let { " ($it)" } ?: ""}",
                "frequência ${tuner.frequencyKhz} kHz, mudo=${tuner.muted}, faixa ${tuner.region}",
            )
        }
        section("Aparelho") { device() }
        section("Propriedades") { properties() }
        section("Recursos de rádio") { features(context) + classes() }
        val nwd = runCatching { NwdProbe.collect(context) }.getOrElse { listOf("erro: ${it.message ?: it}") }
        section("Rádio NWD (com.nwd.radio.service)") { nwd }
        section("Registro do rádio NWD") { radioLog.ifEmpty { listOf("vazio: o sintonizador NWD ainda não foi usado desde que o app abriu") } }
        val fyt = runCatching { fytProbe(context) }.getOrElse { listOf("erro: ${it.message ?: it}") }
        section("Serviço FYT (com.syu.ms)") { fyt }
        section("Pacotes do fabricante") { packages(context) }
        section("Configurações guardadas") { settings(context) }
        section("Serviços do sistema") { binderServices() }
        section("Dispositivos em /dev") { devices() }
        report.toString().trimEnd()
    }

    private fun device(): List<String> = buildList {
        add("fabricante ${Build.MANUFACTURER}, marca ${Build.BRAND}, modelo ${Build.MODEL}")
        add("device ${Build.DEVICE}, produto ${Build.PRODUCT}, placa ${Build.BOARD}, hardware ${Build.HARDWARE}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        add("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("build ${Build.DISPLAY}")
        add("fingerprint ${Build.FINGERPRINT}")
    }

    /** System properties that name the platform, the MCU or the radio. Serial numbers are left out. */
    private fun properties(): List<String> {
        val output = exec("/system/bin/getprop") ?: return listOf("getprop não respondeu")
        return output.lineSequence()
            .mapNotNull { PROP_LINE.matchEntire(it.trim()) }
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { (key, value) ->
                !PRIVATE_PROP.containsMatchIn(key) &&
                    (PLATFORM_PROP.containsMatchIn(key) || VENDOR_WORDS.containsMatchIn(key) || VENDOR_WORDS.containsMatchIn(value))
            }
            .map { (key, value) -> "$key = ${value.take(120)}" }
            .take(MAX_LINES)
            .toList()
    }

    private fun features(context: Context): List<String> {
        val pm = context.packageManager
        return RADIO_FEATURES.map { "recurso $it: ${if (pm.hasSystemFeature(it)) "sim" else "não"}" }
    }

    /** Framework classes that only exist on some platforms. Looked up without running their static code. */
    private fun classes(): List<String> = PLATFORM_CLASSES.map { name ->
        val present = runCatching { Class.forName(name, false, Diagnostics::class.java.classLoader) }.isSuccess
        "classe $name: ${if (present) "presente" else "ausente"}"
    }

    /** Binds com.syu.ms, listens for a moment and reports what it pushes, without sending commands. */
    private suspend fun fytProbe(context: Context): List<String> {
        val lines = mutableListOf<String>()
        val installed = runCatching { context.packageManager.getPackageInfo(Syu.PACKAGE, 0) }.isSuccess
        lines += "pacote ${Syu.PACKAGE}: ${if (installed) "instalado" else "não encontrado"}"
        val toolkit = try {
            withTimeout(FYT_CONNECT_MS) { SyuToolkitClient.connect(context) }
        } catch (e: Exception) {
            lines += "ligação: falhou (${e.message ?: e})"
            return lines
        }
        lines += "ligação: ok"
        val updates = mutableListOf<String>()
        toolkit.listener = { update -> synchronized(updates) { updates += describe(update) } }
        try {
            for ((module, codes) in FYT_PROBE) {
                val remote = toolkit.module(module)
                lines += "módulo $module: ${if (remote == null) "ausente" else "ok"}"
                codes.forEach { remote?.register(it) }
            }
            delay(FYT_LISTEN_MS)
        } finally {
            toolkit.listener = null
            runCatching { toolkit.close() }
        }
        synchronized(updates) { lines += if (updates.isEmpty()) listOf("nenhuma atualização recebida") else updates }
        return lines
    }

    private fun describe(update: SyuUpdate): String {
        val ints = update.ints?.joinToString(",", "[", "]") ?: "-"
        val strings = update.strings?.joinToString(",", "[", "]") { it.orEmpty().take(40) } ?: "-"
        return "módulo ${update.module} código ${update.code}: ints $ints textos $strings"
    }

    /**
     * Packages that are not plain Android or Google, plus any whose name mentions the radio. For
     * the vendor's own packages every component is listed, since one of them drives the tuner.
     */
    private fun packages(context: Context): List<String> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val all = pm.getInstalledPackages(0)
            .filter { it.packageName != context.packageName }
            .filter { RADIO_WORDS.containsMatchIn(it.packageName) || STOCK_PREFIXES.none { p -> it.packageName.startsWith(p) } }
        // The head-unit maker's system apps first: one of them drives the tuner. The chip maker's
        // own apps (com.sprd, com.unisoc) only when their name mentions the radio.
        fun detailed(info: PackageInfo): Boolean {
            val name = info.packageName
            val system = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            return RADIO_WORDS.containsMatchIn(name) || (system && SOC_PREFIXES.none { name.startsWith(it) })
        }
        val (vendor, others) = all.partition(::detailed)
        return (vendor.sortedBy { it.packageName }.map { it.packageName to true } +
            others.sortedBy { it.packageName }.map { it.packageName to false }).flatMap { (name, full) ->
            // One package at a time: asking for every component of every package at once can
            // overflow the Binder buffer.
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(name, if (full) COMPONENT_FLAGS else 0)
            }.getOrNull() ?: return@flatMap listOf("$name (sem acesso)")
            val app = info.applicationInfo
            val system = (app?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            val label = app?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
            val head = buildString {
                append(name)
                if (system) append(" [sistema]")
                label?.let { append(" \"").append(it).append('"') }
                info.versionName?.let { append(' ').append(it) }
                info.sharedUserId?.let { append(" uid=").append(it) }
            }
            if (!full) return@flatMap listOf(head)
            listOf(head) + components(info)
        }.take(MAX_PACKAGE_LINES)
    }

    private fun components(info: PackageInfo): List<String> = buildList {
        fun line(kind: String, item: ComponentInfo, permission: String?, extra: String? = null) {
            add(buildString {
                append("    ").append(kind).append(' ').append(item.name)
                if (item.exported) append(" (exportado)")
                if (!item.enabled) append(" (desativado)")
                permission?.let { append(" perm=").append(it) }
                extra?.let { append(' ').append(it) }
            })
        }
        info.services.orEmpty().forEach { line("serviço", it, it.permission) }
        info.receivers.orEmpty().forEach { line("receptor", it, it.permission) }
        info.providers.orEmpty().forEach { line("provedor", it, it.readPermission, "autoridade=${it.authority}") }
        info.activities.orEmpty().filter { it.exported }.forEach { line("tela", it, it.permission) }
        info.permissions.orEmpty().forEach { permission ->
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                permission.protection
            } else {
                @Suppress("DEPRECATION")
                permission.protectionLevel and 0xf
            }
            add("    declara permissão ${permission.name} (nível $level)")
        }
    }

    /** Settings the vendor may keep the radio's state in, like the last frequency. */
    private fun settings(context: Context): List<String> = listOf(
        "system" to Settings.System.CONTENT_URI,
        "global" to Settings.Global.CONTENT_URI,
    ).flatMap { (table, uri) ->
        runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val key = cursor.getString(0) ?: continue
                        if (!PRIVATE_PROP.containsMatchIn(key) && (SETTING_WORDS.containsMatchIn(key) || VENDOR_WORDS.containsMatchIn(key))) {
                            add("$table $key = ${cursor.getString(1)?.take(120)}")
                        }
                    }
                }
            } ?: listOf("$table: sem resposta")
        }.getOrElse { listOf("$table: ${it.message ?: it}") }
    }.take(MAX_LINES)

    private fun binderServices(): List<String> {
        val output = exec("/system/bin/service", "list") ?: return listOf("service list não respondeu")
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val matching = lines.filter { VENDOR_WORDS.containsMatchIn(it) || RADIO_WORDS.containsMatchIn(it) }
        return listOf("${lines.size} serviços no total") + matching.take(MAX_LINES)
    }

    private fun devices(): List<String> {
        val names = File("/dev").list() ?: return listOf("sem acesso a /dev")
        return names.filter { DEV_WORDS.containsMatchIn(it) }.sorted().take(MAX_LINES)
    }

    /** Runs a system tool and returns its output, or null when it fails or hangs. */
    internal fun exec(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { output.append(it.readText()) } }
        }.apply { start() }
        if (!process.waitFor(TOOL_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        reader.join(1_000)
        output.toString()
    }.getOrNull()

    private const val MAX_LINES = 150
    private const val MAX_PACKAGE_LINES = 400
    private const val TOOL_TIMEOUT_S = 4L
    private const val FYT_CONNECT_MS = 4_000L
    private const val FYT_LISTEN_MS = 2_000L

    /** module to update codes: source and mute, frequency, band info, stereo and search state. */
    private val FYT_PROBE = listOf(
        Syu.MODULE_MAIN to listOf(Syu.Main.U_APP_ID),
        Syu.MODULE_SOUND to listOf(Syu.Sound.U_MUTE),
        Syu.MODULE_RADIO to listOf(Syu.Radio.U_FREQ, Syu.Radio.U_EXTRA_FREQ_INFO, Syu.Radio.U_STEREO, Syu.Radio.U_SEARCH_STATE),
    )

    private val PROP_LINE = Regex("""\[(.+?)]: \[(.*)]""")
    private val PRIVATE_PROP = Regex("""(?i)serial|imei|mac|wifi\.addr|bt\.addr|bluetooth\.addr|password|token""")
    private val PLATFORM_PROP = Regex(
        """^ro\.(product\.(model|brand|manufacturer|device|name|board)|board\.|build\.(display\.id|version\.(release|incremental)|product|flavor)|hardware|boot\.hardware|chipname|soc\.|fota\.|vendor\.product\.)""",
    )
    /** Words that point at a head-unit vendor or its MCU. NWD (K2001, K2401, K2501, K4811) is this unit's. */
    private val VENDOR_WORDS = Regex(
        """(?i)nwd|k4811|k2[045]01|ruancheng|syu|fyt|mcu|topway|\btw\b|tw\.|twutil|microntek|mtc[bcde]|hzbhd|bhd\.|\bqf\b|qf\.|kld|yt9|yt\.|zlink|sprd|unisoc|8581|7862|7870|9863|sc98|px[56]|allwinner|sun8i|navradio|carradio|qn80""",
    )
    private val RADIO_WORDS = Regex("""(?i)radio|\bfm\b|fmradio|\.fm|tuner|syu|mcu|carradio""")
    private val SETTING_WORDS = Regex("""(?i)radio|\bfm|fm_|freq|tuner|band|source|\bsrc|mute|audio_|car_|canbus""")
    private val DEV_WORDS = Regex("""(?i)fm|radio|i2c|mcu|qn|syu|tw|tty[smh]""")

    @Suppress("DEPRECATION")
    private const val COMPONENT_FLAGS = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
        PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_PERMISSIONS or
        PackageManager.GET_DISABLED_COMPONENTS

    private val STOCK_PREFIXES = listOf("com.google.", "com.android.", "android", "org.chromium.", "com.qualcomm.")
    private val SOC_PREFIXES = listOf("com.sprd.", "com.unisoc.", "com.spreadtrum.")

    private val RADIO_FEATURES = listOf("android.hardware.broadcastradio", "android.hardware.type.automotive")

    /** In the framework of TopWay (TWUtil), Microntek MTC and NWD units only. */
    private val PLATFORM_CLASSES = listOf(
        "android.tw.john.TWUtil",
        "android.microntek.CarManager",
        "android.microntek.mtcser.BTServiceInf",
        "com.nwd.app.NwdFmManager",
    )
}
