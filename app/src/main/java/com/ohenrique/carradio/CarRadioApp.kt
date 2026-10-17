package com.ohenrique.carradio

import android.app.Application
import com.ohenrique.carradio.data.RadioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CarRadioApp : Application() {

    lateinit var radio: RadioController
        private set

    override fun onCreate() {
        super.onCreate()
        radio = RadioController(RadioStore(this), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
}
