package com.ohenrique.carradio

import android.app.Application
import com.ohenrique.carradio.data.RadioStore
import com.ohenrique.carradio.fyt.SyuToolkitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CarRadioApp : Application() {

    lateinit var radio: RadioController
        private set

    override fun onCreate() {
        super.onCreate()
        radio = RadioController(
            store = RadioStore(this),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            connectFyt = { SyuToolkitClient.connect(this) },
        )
    }
}
