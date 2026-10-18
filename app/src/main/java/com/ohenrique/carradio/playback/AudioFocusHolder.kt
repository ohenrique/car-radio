package com.ohenrique.carradio.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * The app's one Android audio focus request, shared by the playback service, which asks for it,
 * and the radio controller, which gives it up before starting a tuner that manages the head unit's
 * audio source. On NWD units the system stops an app that still holds the focus when the MCU
 * switches away from Android, so that release must not wait for the service to notice.
 */
class AudioFocusHolder(context: Context) {

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    @Volatile private var onChange: ((Int) -> Unit)? = null

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { change -> onChange?.invoke(change) }
        .build()

    /** Asks for the focus; [listener] gets its changes until [abandon]. */
    @Synchronized
    fun request(listener: (Int) -> Unit) {
        onChange = listener
        audioManager?.requestAudioFocus(request)
    }

    @Synchronized
    fun abandon() {
        onChange = null
        audioManager?.abandonAudioFocusRequest(request)
    }
}
