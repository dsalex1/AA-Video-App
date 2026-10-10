package de.purlife.akademieauto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps the app process running while it's in the background or the screen is off, so audio/video in the
 * WebView keeps playing (Android otherwise freezes background apps after a few seconds).
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hintergrundwiedergabe", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Wiedergabe läuft auch im Hintergrund weiter")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1

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
