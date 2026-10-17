package com.ohenrique.carradio.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
import com.ohenrique.carradio.tuner.TunerState
import com.ohenrique.carradio.tuner.formatMhz
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/**
 * Keeps the radio alive in the background and publishes it as a media session, so the steering
 * wheel's next/previous keys, the notification and other media controllers can drive it.
 */
class RadioPlaybackService : Service() {

    private val scope = MainScope()
    private val radio get() = (application as CarRadioApp).radio
    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var mutedByFocusLoss = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
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
        requestFocus()
        scope.launch {
            radio.state.collect { state ->
                updateSession(state)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(session, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            requestFocus()
            radio.setMuted(false)
        }

        override fun onPause() = radio.setMuted(true)

        override fun onStop() = radio.setMuted(true)

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

    private fun requestFocus() {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
            .also { focusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    private fun onFocusChange(change: Int) {
        // On FYT units the MCU switches the speakers between sources by itself, and muting here
        // could silence the app that just took over.
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
