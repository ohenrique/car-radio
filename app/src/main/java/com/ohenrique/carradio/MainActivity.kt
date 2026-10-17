package com.ohenrique.carradio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.ohenrique.carradio.playback.RadioPlaybackService
import com.ohenrique.carradio.ui.RadioScreen
import com.ohenrique.carradio.ui.RadioTheme
import com.ohenrique.carradio.ui.RadioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: RadioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        requestNotificationPermission()
        setContent {
            RadioTheme {
                RadioScreen(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as CarRadioApp).radio.start()
        RadioPlaybackService.start(this)
    }

    /** The notification carries the media controls; the radio works without it. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }
}
