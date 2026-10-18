package com.ohenrique.carradio

import android.app.Application
import com.ohenrique.carradio.data.RadioStore
import com.ohenrique.carradio.diag.EventLog
import com.ohenrique.carradio.fyt.SyuToolkitClient
import com.ohenrique.carradio.nwd.NwdServiceClient
import com.ohenrique.carradio.playback.AudioFocusHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CarRadioApp : Application() {

    lateinit var radio: RadioController
        private set

    lateinit var audioFocus: AudioFocusHolder
        private set

    /** What the tuner did in real use, for the diagnostics screen. */
    val radioLog = EventLog()

    override fun onCreate() {
        super.onCreate()
        audioFocus = AudioFocusHolder(this)
        radio = RadioController(
            store = RadioStore(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            connectFyt = { SyuToolkitClient.connect(this) },
            connectNwd = { NwdServiceClient.connect(this) },
            detectBackend = { detectBackend(this) },
            releaseAudioFocus = audioFocus::abandon,
            trace = radioLog::add,
        )
    }
}
