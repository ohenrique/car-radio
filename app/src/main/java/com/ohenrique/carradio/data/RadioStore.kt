package com.ohenrique.carradio.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ohenrique.carradio.tuner.Region
import com.ohenrique.carradio.tuner.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

enum class TunerBackend { QN8035, SIMULATOR }

/** What the steering wheel's next/previous buttons do. */
enum class SteeringWheelAction { PRESETS, SEEK }

data class RadioSettings(
    val backend: TunerBackend = TunerBackend.QN8035,
    /** I2C bus the QN8035 sits on, or null to find it automatically. */
    val i2cBus: Int? = null,
    val i2cAddress: Int = 0x10,
    val clockHz: Int = 32_768,
    val region: Region = Region.BRAZIL,
    val sensitivity: Sensitivity = Sensitivity.DX,
    val forceMono: Boolean = false,
    val rdsEnabled: Boolean = true,
    val steeringWheel: SteeringWheelAction = SteeringWheelAction.PRESETS,
)

data class Preset(val frequencyKhz: Int, val name: String? = null)

const val PRESET_PAGES = 3
const val PRESETS_PER_PAGE = 6
const val PRESET_COUNT = PRESET_PAGES * PRESETS_PER_PAGE

private val Context.dataStore by preferencesDataStore(name = "radio")

/** Settings, the 18 presets (FM1 to FM3, 6 each) and the last station, kept across restarts. */
class RadioStore(private val context: Context) {

    val settings: Flow<RadioSettings> = context.dataStore.data.map { it.toSettings() }

    val presets: Flow<List<Preset?>> = context.dataStore.data.map { decodePresets(it[PRESETS]) }

    suspend fun lastFrequencyKhz(): Int? = context.dataStore.data.first()[LAST_FREQUENCY]

    suspend fun saveLastFrequency(frequencyKhz: Int) {
        context.dataStore.edit { it[LAST_FREQUENCY] = frequencyKhz }
    }

    suspend fun updateSettings(transform: (RadioSettings) -> RadioSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[BACKEND] = s.backend.name
            prefs[I2C_BUS] = s.i2cBus ?: -1
            prefs[I2C_ADDRESS] = s.i2cAddress
            prefs[CLOCK_HZ] = s.clockHz
            prefs[REGION] = s.region.name
            prefs[SENSITIVITY] = s.sensitivity.name
            prefs[FORCE_MONO] = s.forceMono
            prefs[RDS] = s.rdsEnabled
            prefs[STEERING_WHEEL] = s.steeringWheel.name
        }
    }

    suspend fun updatePresets(transform: (List<Preset?>) -> List<Preset?>) {
        context.dataStore.edit { prefs ->
            prefs[PRESETS] = encodePresets(transform(decodePresets(prefs[PRESETS])))
        }
    }

    private fun Preferences.toSettings(): RadioSettings {
        val defaults = RadioSettings()
        return RadioSettings(
            backend = enumOrDefault(this[BACKEND], defaults.backend),
            i2cBus = this[I2C_BUS]?.takeIf { it >= 0 },
            i2cAddress = this[I2C_ADDRESS] ?: defaults.i2cAddress,
            clockHz = this[CLOCK_HZ] ?: defaults.clockHz,
            region = enumOrDefault(this[REGION], defaults.region),
            sensitivity = enumOrDefault(this[SENSITIVITY], defaults.sensitivity),
            forceMono = this[FORCE_MONO] ?: defaults.forceMono,
            rdsEnabled = this[RDS] ?: defaults.rdsEnabled,
            steeringWheel = enumOrDefault(this[STEERING_WHEEL], defaults.steeringWheel),
        )
    }

    private companion object {
        val BACKEND = stringPreferencesKey("backend")
        val I2C_BUS = intPreferencesKey("i2c_bus")
        val I2C_ADDRESS = intPreferencesKey("i2c_address")
        val CLOCK_HZ = intPreferencesKey("clock_hz")
        val REGION = stringPreferencesKey("region")
        val SENSITIVITY = stringPreferencesKey("sensitivity")
        val FORCE_MONO = booleanPreferencesKey("force_mono")
        val RDS = booleanPreferencesKey("rds")
        val STEERING_WHEEL = stringPreferencesKey("steering_wheel")
        val PRESETS = stringPreferencesKey("presets")
        val LAST_FREQUENCY = intPreferencesKey("last_frequency")

        inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == name } ?: default

        fun decodePresets(json: String?): List<Preset?> {
            val slots = MutableList<Preset?>(PRESET_COUNT) { null }
            if (json == null) return slots
            runCatching {
                val array = JSONArray(json)
                for (i in 0 until minOf(array.length(), PRESET_COUNT)) {
                    val item = array.optJSONObject(i) ?: continue
                    slots[i] = Preset(item.getInt("khz"), item.optString("name").ifEmpty { null })
                }
            }
            return slots
        }

        fun encodePresets(presets: List<Preset?>): String = JSONArray().apply {
            presets.take(PRESET_COUNT).forEach { preset ->
                put(preset?.let { JSONObject().put("khz", it.frequencyKhz).put("name", it.name ?: "") } ?: JSONObject.NULL)
            }
        }.toString()
    }
}
