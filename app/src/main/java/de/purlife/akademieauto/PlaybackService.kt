package de.purlife.akademieauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle

/**
 * Keeps the app process running while it's in the background or the screen is off (Android otherwise freezes
 * background apps), and shows a media notification / lock-screen controls for whatever plays in the WebViews.
 */
class PlaybackService : Service() {
    private lateinit var session: MediaSessionCompat
    private val onMediaChanged: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Wiedergabe", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSessionCompat(this, "AkademieAuto").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = MediaHub.command("play")
                override fun onPause() = MediaHub.command("pause")
                override fun onSeekTo(pos: Long) = MediaHub.command("seek:${pos / 1000.0}")
                override fun onRewind() = MediaHub.command("skip:-$REWIND_S")
                override fun onFastForward() = MediaHub.command("skip:$FORWARD_S")
                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        ACTION_REWIND -> onRewind()
                        ACTION_FORWARD -> onFastForward()
                    }
                }
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        MediaHub.addListener(onMediaChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> MediaHub.command("play")
            ACTION_PAUSE -> MediaHub.command("pause")
            ACTION_REWIND -> MediaHub.command("skip:-$REWIND_S")
            ACTION_FORWARD -> MediaHub.command("skip:$FORWARD_S")
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaHub.removeListener(onMediaChanged)
        session.release()
        super.onDestroy()
    }

    private fun update() {
        val media = MediaHub.current
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, media?.title?.ifBlank { null } ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, media?.artist ?: "")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, media?.durationMs ?: 0)
                .build()
        )
        val playing = media?.playing == true
        // Extrapolate from the last report, so the progress bar is right even between reports.
        val position = media?.let { it.positionMs + if (playing) SystemClock.elapsedRealtime() - it.updatedAt else 0 } ?: 0
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_REWIND or PlaybackStateCompat.ACTION_FAST_FORWARD
                )
                .addCustomAction(ACTION_REWIND, "−$REWIND_S s", R.drawable.ic_rewind)
                .addCustomAction(ACTION_FORWARD, "+$FORWARD_S s", R.drawable.ic_forward)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    position, if (playing) 1f else 0f, SystemClock.elapsedRealtime(),
                )
                .build()
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val media = MediaHub.current
        val playing = media?.playing == true
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(media?.title?.ifBlank { null } ?: getString(R.string.app_name))
            .setContentText(media?.artist ?: "Läuft auch im Hintergrund weiter")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_rewind, "−$REWIND_S s", serviceIntent(ACTION_REWIND))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Abspielen",
                serviceIntent(if (playing) ACTION_PAUSE else ACTION_PLAY),
            )
            .addAction(R.drawable.ic_forward, "+$FORWARD_S s", serviceIntent(ACTION_FORWARD))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceIntent(action: String) = PendingIntent.getService(
        this, action.hashCode(), Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val REWIND_S = 10
        private const val FORWARD_S = 30
        private const val ACTION_PLAY = "play"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_REWIND = "rewind"
        private const val ACTION_FORWARD = "forward"

        /** Must be called while the app is visible (Android blocks starting it from the background). */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w("PlaybackService", "Could not start", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
