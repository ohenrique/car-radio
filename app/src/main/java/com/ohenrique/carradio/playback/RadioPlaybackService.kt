package com.ohenrique.carradio.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.ohenrique.carradio.CarRadioApp
import com.ohenrique.carradio.MainActivity
import com.ohenrique.carradio.R
import com.ohenrique.carradio.data.SteeringWheelAction
import com.ohenrique.carradio.tuner.PowerState
import com.ohenrique.carradio.tuner.SeekDirection
import com.ohenrique.carradio.tuner.Tuner
import com.ohenrique.carradio.tuner.TunerState
import com.ohenrique.carradio.tuner.formatMhz
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Keeps the radio alive in the background and publishes it as a media session, so the steering
 * wheel's next/previous keys, the notification and other media controllers can drive it.
 */
class RadioPlaybackService : Service() {

    private val scope = MainScope()
    private val radio get() = (application as CarRadioApp).radio
    private val focus get() = (application as CarRadioApp).audioFocus
    private lateinit var session: MediaSessionCompat
    private var mutedByFocusLoss = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "CarRadio").apply {
            setCallback(SessionCallback())
            setSessionActivity(openAppIntent())
            isActive = true
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(radio.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        scope.launch {
            radio.state.collect { state ->
                updateSession(state)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        scope.launch {
            combine(radio.tuner, radio.state) { tuner, state -> wantsFocus(tuner, state) }
                .distinctUntilChanged()
                .collect { wanted -> if (wanted) requestFocus() else focus.abandon() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        focus.abandon()
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (wantsFocus()) requestFocus()
            radio.setMuted(false)
        }

        override fun onPause() = radio.setMuted(true)

        override fun onStop() {
            // NWD firmware sends a stop key while it switches the MCU to the radio; muting on it
            // would silence the radio right after the app claimed the speakers.
            if (!radio.tuner.value.managesAudioSource) radio.setMuted(true)
        }

        override fun onSkipToNext() = skip(+1)

        override fun onSkipToPrevious() = skip(-1)

        override fun onFastForward() = radio.seek(SeekDirection.UP)

        override fun onRewind() = radio.seek(SeekDirection.DOWN)
    }

    private fun skip(delta: Int) {
        when (radio.settings.value?.steeringWheel ?: SteeringWheelAction.PRESETS) {
            SteeringWheelAction.PRESETS -> radio.nextPreset(delta)
            SteeringWheelAction.SEEK -> radio.seek(if (delta > 0) SeekDirection.UP else SeekDirection.DOWN)
        }
    }

    /**
     * Android's audio focus is only for a tuner that is on and whose sound Android routes. Where the
     * MCU switches the speakers between sources (FYT, NWD) the focus means nothing, and NWD's system
     * even stops an app that holds it when the MCU leaves Android.
     */
    private fun wantsFocus(tuner: Tuner = radio.tuner.value, state: TunerState = radio.state.value): Boolean =
        state.power == PowerState.ON && !tuner.managesAudioSource

    private fun requestFocus() = focus.request(::onFocusChange)

    private fun onFocusChange(change: Int) {
        // Where the MCU switches the speakers between sources by itself, muting here could silence
        // the app that just took over.
        if (radio.tuner.value.managesAudioSource) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (mutedByFocusLoss) {
                mutedByFocusLoss = false
                radio.setMuted(false)
            }
            // A phone call or a navigation prompt: mute until it is over.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!radio.state.value.muted) {
                mutedByFocusLoss = true
                radio.setMuted(true)
            }
            // Another player took over for good.
            AudioManager.AUDIOFOCUS_LOSS -> {
                mutedByFocusLoss = false
                radio.setMuted(true)
            }
        }
    }

    private fun updateSession(state: TunerState) {
        val playing = state.power == PowerState.ON && !state.muted
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title(state))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle(state))
                .build(),
        )
    }

    private fun buildNotification(state: TunerState): Notification {
        val playing = state.power == PowerState.ON && !state.muted
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(title(state))
            .setContentText(subtitle(state))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_skip_previous, "Anterior", mediaAction(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_volume_up else R.drawable.ic_volume_off,
                if (playing) "Silenciar" else "Ouvir",
                mediaAction(PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
            .addAction(R.drawable.ic_skip_next, "Próxima", mediaAction(PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun title(state: TunerState) = state.rds.programService ?: "${formatMhz(state.frequencyKhz)} FM"

    private fun subtitle(state: TunerState) = state.rds.radioText ?: "FM ${formatMhz(state.frequencyKhz)} MHz"

    private fun mediaAction(action: Long) = MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

    private fun openAppIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Rádio", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "radio"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RadioPlaybackService::class.java))
        }
    }
}
