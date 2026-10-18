package com.ohenrique.carradio.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ohenrique.carradio.nwd.NwdServiceClient
import com.ohenrique.carradio.nwd.quoted
import com.ohenrique.carradio.tuner.nwd.Nwd
import com.ohenrique.carradio.tuner.nwd.NwdBandPlan
import com.ohenrique.carradio.tuner.nwd.NwdFrequency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest

/**
 * Looks at the NWD radio without changing anything: the vendor's packages and their versions, who
 * receives the broadcasts the tuner sends, the MCU's mirrors in `Settings.System`, every getter of
 * com.nwd.radio.service, and what the service and the vendor's apps report on their own for a
 * moment. It sends no command, no broadcast and no setting, and does not start the service; the
 * only calls besides getters are registering and removing its own callback.
 */
internal object NwdProbe {

    suspend fun collect(context: Context): List<String> {
        val lines = mutableListOf<String>()
        fun step(block: () -> List<String>) {
            lines += runCatching(block).getOrElse { listOf("erro: ${it.message ?: it}") }
        }
        step { packages(context) }
        step { resolution(context) }
        step { receivers(context) }
        step { properties() }
        step { settings(context) }
        step { listOf("modo de áudio do Android: ${audioMode(context)}") }
        lines += try {
            service(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf("serviço: erro (${e.message ?: e})")
        }
        return lines
    }

    /** Installed, enabled, versions and APK of each vendor package; hashes to compare with Radio+'s. */
    private fun packages(context: Context): List<String> {
        val pm = context.packageManager
        return PACKAGES.flatMap { name ->
            @Suppress("DEPRECATION")
            val info = runCatching { pm.getPackageInfo(name, 0) }.getOrNull() ?: return@flatMap listOf("$name: não instalado")
            val app = info.applicationInfo
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            buildList {
                add(buildString {
                    append(name).append(": versão ").append(info.versionName).append(" (").append(code).append(')')
                    if (app != null) {
                        if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) append(" [sistema]")
                        append(if (app.enabled) ", ativo" else ", desativado")
                        append(", targetSdk ").append(app.targetSdkVersion)
                    }
                })
                val apk = app?.sourceDir
                if (apk != null) {
                    add("    apk $apk")
                    if (name in HASHED) add("    sha256 ${hashOf(File(apk), compare = name in COMPARED)}")
                }
                app?.splitSourceDirs?.forEach { add("    parte $it") }
            }
        }
    }

    private fun hashOf(file: File, compare: Boolean): String {
        if (!file.canRead()) return "sem acesso ao arquivo"
        if (file.length() > MAX_HASH_BYTES) return "não calculado (${file.length() / 1_000_000} MB)"
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!compare) return hash
        val match = RADIO_PLUS_APKS[hash]
        return "$hash (${if (match != null) "igual ao $match analisado pelo Radio+" else "diferente dos analisados pelo Radio+"})"
    }

    /** What answers the radio service's action. */
    private fun resolution(context: Context): List<String> {
        val services = NwdServiceClient.radioServices(context.packageManager)
        if (services.isEmpty()) return listOf("ação ${Nwd.RADIO_SERVICE_ACTION}: nenhum serviço responde")
        return services.map {
            "ação ${Nwd.RADIO_SERVICE_ACTION}: ${it.packageName}/${it.name}, " +
                "exportado ${if (it.exported) "sim" else "não"}, permissão ${it.permission ?: "nenhuma"}"
        }
    }

    /** Manifest receivers of the broadcasts the tuner sends, to tell how they should be delivered. */
    private fun receivers(context: Context): List<String> {
        val pm = context.packageManager
        return SENT_ACTIONS.map { action ->
            @Suppress("DEPRECATION")
            val found = runCatching { pm.queryBroadcastReceivers(Intent(action), 0) }.getOrNull().orEmpty().mapNotNull { it.activityInfo }
            if (found.isEmpty()) {
                "$action: nenhum receptor no manifesto"
            } else {
                "$action: " + found.joinToString { "${it.packageName}/${it.name} (targetSdk ${it.applicationInfo?.targetSdkVersion})" }
            }
        } + "Receptores registrados em tempo de execução não aparecem nesta lista."
    }

    private fun properties(): List<String> = PROPERTIES.map { key ->
        val value = Diagnostics.exec("/system/bin/getprop", key)?.trim()
        "$key = ${if (value.isNullOrEmpty()) "(vazio)" else value}"
    }

    private fun settings(context: Context): List<String> = SETTINGS.map { key ->
        val value = try {
            Settings.System.getString(context.contentResolver, key)
        } catch (e: SecurityException) {
            return@map "$key: sem acesso"
        }
        "$key = ${value ?: "ausente"}"
    }

    private fun audioMode(context: Context): String {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return "desconhecido"
        val name = when (mode) {
            AudioManager.MODE_NORMAL -> "normal"
            AudioManager.MODE_RINGTONE -> "tocando"
            AudioManager.MODE_IN_CALL -> "em ligação"
            AudioManager.MODE_IN_COMMUNICATION -> "em comunicação"
            else -> "outro"
        }
        return "$mode ($name)"
    }

    /** Binds the service without starting it, reads every getter, then listens for a moment. */
    private suspend fun service(context: Context): List<String> {
        val started = System.nanoTime()
        val client = try {
            withTimeout(CONNECT_MS) { NwdServiceClient.connect(context, warmUp = false) }
        } catch (e: TimeoutCancellationException) {
            return listOf("ligação: sem resposta em $CONNECT_MS ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf("ligação: falhou (${e.message ?: e})")
        }
        val lines = mutableListOf<String>()
        fun get(label: String, read: () -> Any?) {
            lines += "$label: " + runCatching { read().toString() }.getOrElse { "erro (${it.message ?: it})" }
        }
        try {
            lines += "ligação: ok em ${millisSince(started)} ms"
            get("descritor") { client.interfaceDescriptor() }
            get("getRadioType") { client.getRadioType().let { "$it (${typeName(it)})" } }
            val points = runCatching { client.getRadioPoint() }
            lines += "getRadioPoint: " + points.fold(
                { list -> list?.joinToString(", ", "[", "]") { it?.let { p -> "${p.min}/${p.max}/${p.step}" } ?: "null" } ?: "null" },
                { "erro (${it.message ?: it})" },
            )
            val plan = NwdBandPlan.fromRadioPoints(points.getOrNull())
            lines += "faixa FM: " + (
                plan?.let { "${it.minRaw} a ${it.maxRaw}, passo ${it.stepRaw}, unidade ${it.scaleKhz} kHz, região ${it.region}" }
                    ?: "não reconhecida no getRadioPoint"
                )
            get("getCurrentFrequency") { client.getCurrentFrequency()?.let { describe(it, plan) } }
            get("getRadioState") { client.getRadioState() }
            get("getCurrentScanState") { client.getCurrentScanState() }
            get("isNearOn") { client.isNearOn() }
            get("isHasStrero") { client.hasStereoPilot() }
            get("isStreroOn") { client.isStereoOn() }
            get("isRadioBackServiceOn") { client.isRadioBackServiceOn() }
            get("getRtMessage") { quoted(client.getRtMessage()) }
            get("getPTYType") { client.getPtyType() }
            RDS_FLAGS.forEach { flag -> get("getRDSState($flag)") { client.getRdsState(flag) } }
            get("getPrefabFrequency") {
                client.getPrefabFrequency()?.let { list ->
                    "${list.size} entradas: " + list.joinToString { f -> f?.let { "${it.band}/${it.frequency}/${quoted(it.programService)}" } ?: "null" }
                }
            }
            lines += listen(context, client)
        } finally {
            runCatching { client.close() }
        }
        return lines
    }

    /**
     * Registers a callback and listens for [LISTEN_MS]: callbacks, the vendor's status broadcasts
     * (each extra with its Java type) and changes of the MCU's settings, each with its time.
     */
    private suspend fun listen(context: Context, client: NwdServiceClient): List<String> {
        val lines = mutableListOf<String>()
        val heard = mutableListOf<String>()
        val start = System.nanoTime()
        fun hear(line: String) = synchronized(heard) {
            if (heard.size < MAX_HEARD) heard += "    +${millisSince(start)} ms $line"
        }
        val main = Handler(Looper.getMainLooper())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                hear("aviso ${intent.action}: ${extrasOf(intent)}")
            }
        }
        val observers = WATCHED_SETTINGS.map { key ->
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    val value = runCatching { Settings.System.getString(context.contentResolver, key) }.getOrNull()
                    hear("$key = ${value ?: "ausente"}")
                }
            } to key
        }
        client.onNotice = { code, call -> hear("callback $code $call") }
        val filter = IntentFilter().apply { HEARD_ACTIONS.forEach(::addAction) }
        val registered = runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }.isSuccess
        observers.forEach { (observer, key) ->
            runCatching { context.contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer) }
        }
        try {
            lines += "registCallback: " + runCatching { client.registerCallback(); "ok" }.getOrElse { "erro (${it.message ?: it})" }
            delay(LISTEN_MS)
            lines += "unRegistCallback: " + runCatching { client.unregisterCallback(); "ok" }.getOrElse { "erro (${it.message ?: it})" }
        } finally {
            client.onNotice = null
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            observers.forEach { (observer, _) -> runCatching { context.contentResolver.unregisterContentObserver(observer) } }
        }
        lines += "em ${LISTEN_MS} ms chegaram:"
        lines += synchronized(heard) { heard.toList() }.ifEmpty { listOf("    nada") }
        return lines
    }

    private fun describe(frequency: NwdFrequency, plan: NwdBandPlan?): String {
        val raw = frequency.frequency
        val khz = when {
            Nwd.Band.isFm(frequency.band) -> plan?.toKhz(raw) ?: NwdBandPlan.scaleOf(raw)?.let { raw * it }
            Nwd.Band.isAm(frequency.band) -> raw
            else -> null
        }
        return "banda ${frequency.band}, nome ${quoted(frequency.programService)}, frequência $raw" +
            (khz?.let { " ($it kHz)" } ?: "")
    }

    @Suppress("DEPRECATION")
    private fun extrasOf(intent: Intent): String = runCatching {
        val extras = intent.extras ?: return@runCatching "sem extras"
        extras.keySet().sorted().joinToString { key ->
            val value = extras.get(key)
            val shown = if (value is String) quoted(value) else value.toString()
            "$key=$shown (${value?.javaClass?.simpleName ?: "null"})"
        }
    }.getOrElse { "extras ilegíveis (${it.message ?: it})" }

    private fun typeName(type: Int) = when (type) {
        Nwd.RadioType.MCU -> "MCU"
        Nwd.RadioType.ARM -> "ARM"
        Nwd.RadioType.ALLWINNER -> "Allwinner"
        Nwd.RadioType.SOC -> "FM do SoC"
        else -> "desconhecido"
    }

    private fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000

    private const val CONNECT_MS = 4_000L
    private const val LISTEN_MS = 2_000L
    private const val MAX_HEARD = 80
    private const val MAX_HASH_BYTES = 100L * 1024 * 1024

    private const val SETTING_SERVICE_PACKAGE = "com.nwd.setting.service"
    private val PACKAGES = listOf(Nwd.RADIO_SERVICE_PACKAGE, Nwd.KERNEL_PACKAGE, Nwd.RADIO_APP_PACKAGE, SETTING_SERVICE_PACKAGE)
    private val HASHED = setOf(Nwd.RADIO_SERVICE_PACKAGE, Nwd.KERNEL_PACKAGE, Nwd.RADIO_APP_PACKAGE)
    private val COMPARED = setOf(Nwd.RADIO_SERVICE_PACKAGE, Nwd.KERNEL_PACKAGE)

    /** SHA-256 of the APKs Radio+ inspected, with the build each came from. */
    private val RADIO_PLUS_APKS = mapOf(
        "0bd82481535166987f37d1d876d2d697393afcef3b46f979ec1a60209ac1fcf8" to "RadioService 2.3.0 da K4811",
        "429685bf6410ae04f6410a62a5c03be2fb57466a0d5d9615f522d1000da6280b" to "KernelService 2.5.0 da K4811",
        "c25dae04c2819cc7e8267b44432928aa4594c131a28562d57cef7148b9262b07" to "RadioService 2.4.2 da G5",
        "13dc810da813623bc38c98e1e69f907329da2906983f9f3d547fc4e681948ab1" to "KernelService 2.6.2 da G5",
        "0eb6df5e6b090c45895369bdee7112f2ec83042f8d5e37fc5e701ee24e0e7842" to "RadioService 2.2.2 da K2401 (Allwinner)",
        "e2de2b8da9bfe96a308e38a86fac75d978b7496a795881b8a921fa930b4d8b47" to "KernelService 2.2.6 da K2401 (Allwinner)",
    )

    /** The vendor also knows these actions without the `action.` segment. */
    private const val REQUEST_CHANGE_SOURCE_SHORT = "com.nwd.ACTION_REQUEST_CHANGE_SOURCE"
    private const val CHANGE_SOURCE_SHORT = "com.nwd.ACTION_CHANGE_SOURCE"

    private val SENT_ACTIONS = listOf(
        Nwd.Action.REQUEST_CHANGE_SOURCE,
        REQUEST_CHANGE_SOURCE_SHORT,
        Nwd.Action.APP_IN_OUT,
        Nwd.Action.MEDIA_PLAY,
        Nwd.Action.SET_MUTE,
        Nwd.Action.CHANGE_SOURCE,
        CHANGE_SOURCE_SHORT,
    )

    private val HEARD_ACTIONS = listOf(
        Nwd.Action.FREQUENCE_NEW,
        Nwd.Action.CHANGE_SOURCE,
        CHANGE_SOURCE_SHORT,
        Nwd.Action.MUTE_STATE_CHANGE,
        Nwd.Action.MCU_STATE_CHANGE,
    )

    private val PROPERTIES = listOf("ro.nwd.platform.name", "ro.product.nwd.platform", "ro.nwd.config.path", "persist.nwd.launcher.default")

    private val WATCHED_SETTINGS = listOf(Nwd.Setting.CURRENT_SOURCE, Nwd.Setting.MUTE_STATE, Nwd.Setting.MCU_STATE)

    /** The MCU's mirrors; the `nwd_radio_*` ones are only known to be written on some units. */
    private val SETTINGS = WATCHED_SETTINGS + listOf(
        "mcu_radio_area_current",
        "mcu_radio_area_feature",
        "nwd_radio_current_freq",
        "nwd_radio_current_band",
        "nwd_radio_current_freq_index",
        "nwd_radio_current_ps_data",
        "nwd_radio_dx_loc_enable",
        "nwd_radio_rds_enable",
        "nwd_radio_back_service_on",
        "nwd_arm_volume_type",
        "mcu_system_volume",
    )

    /** getRDSState flags: the ones the stock clients ask about. */
    private val RDS_FLAGS = listOf(1, 2, 8, 0x80)
}
