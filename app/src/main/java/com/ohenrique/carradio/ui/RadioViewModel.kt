package com.ohenrique.carradio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ohenrique.carradio.CarRadioApp
import com.ohenrique.carradio.data.PRESETS_PER_PAGE
import com.ohenrique.carradio.data.Preset
import com.ohenrique.carradio.data.RadioSettings
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.TunerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class RadioUiState(
    val tuner: TunerState = TunerState(),
    val tunerName: String = "",
    val presets: List<Preset?> = emptyList(),
    val presetPage: Int = 0,
    val settings: RadioSettings = RadioSettings(),
    val scanning: Boolean = false,
    val showSettings: Boolean = false,
) {
    val pagePresets: List<Preset?>
        get() = presets.drop(presetPage * PRESETS_PER_PAGE).take(PRESETS_PER_PAGE)
}

class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val radio = (application as CarRadioApp).radio
    private val presetPage = MutableStateFlow(0)
    private val showSettings = MutableStateFlow(false)

    val uiState: StateFlow<RadioUiState> = combine(
        combine(radio.state, radio.tuner) { state, tuner -> state to tuner.name },
        radio.presets,
        radio.settings,
        radio.scanning,
        combine(presetPage, showSettings) { page, settings -> page to settings },
    ) { (state, tunerName), presets, settings, scanning, (page, showSettings) ->
        RadioUiState(
            tuner = state,
            tunerName = tunerName,
            presets = presets,
            presetPage = page,
            settings = settings ?: RadioSettings(),
            scanning = scanning,
            showSettings = showSettings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState())

    fun tune(frequencyKhz: Int) = radio.tune(frequencyKhz)

    fun step(steps: Int) = radio.step(steps)

    fun seek(direction: SeekDirection) = radio.seek(direction)

    fun toggleMute() = radio.setMuted(!radio.state.value.muted)

    fun toggleSensitivity() = radio.toggleSensitivity()

    fun scan() = radio.scanAndStore()

    fun cancelScan() = radio.cancelOperation()

    fun retry() = radio.start()

    fun selectPage(page: Int) {
        presetPage.value = page
    }

    /** Tapping an empty slot saves the current station there; tapping a saved one tunes to it. */
    fun onPresetTap(slot: Int) {
        val index = presetPage.value * PRESETS_PER_PAGE + slot
        if (radio.presets.value.getOrNull(index) == null) radio.savePreset(index) else radio.selectPreset(index)
    }

    fun onPresetLongPress(slot: Int) = radio.savePreset(presetPage.value * PRESETS_PER_PAGE + slot)

    fun clearPreset(slot: Int) = radio.clearPreset(presetPage.value * PRESETS_PER_PAGE + slot)

    fun showSettings(show: Boolean) {
        showSettings.value = show
    }

    fun updateSettings(transform: (RadioSettings) -> RadioSettings) = radio.updateSettings(transform)
}
