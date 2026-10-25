package com.example.dsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground MediaPlayback Service that hosts the Android 10+ (API 29+) MediaProjection
 * AudioPlaybackCapture session so system media audio can be captured, processed through the
 * 6-stage PsychoacousticDspEngine, delay-compensated via Hermite ring buffers, and routed
 * simultaneously to Phone Loudspeakers + Connected Bluetooth Earphones/Speakers.
 */
class MultiOutputPlaybackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            MultiOutputRouterRegistry.onMediaProjectionStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()

        if (action == ACTION_START_CAPTURE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

            if (resultCode != 0 && resultData != null) {
                try {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val mediaProjection: MediaProjection? = projectionManager.getMediaProjection(resultCode, resultData)
                    if (mediaProjection != null) {
                        MultiOutputRouterRegistry.onMediaProjectionReady(mediaProjection)
                    }
                } catch (_: Exception) {}
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val channelId = "dolby_multi_output_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Dolby Atmos Multi-Output Audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Simultaneous loudspeaker and Bluetooth delay-synced output"
            }
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dolby Atmos")
            .setContentText("Multi-device synchronized audio routing active")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.example.dsp.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.dsp.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 4801

        fun startWithProjection(context: Context, resultCode: Int, resultData: Intent) {
            val serviceIntent = Intent(context, MultiOutputPlaybackService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (_: Exception) {}
        }
    }
}

/**
 * Singleton bridge between MultiOutputPlaybackService and MultiDeviceSyncEngine.
 */
object MultiOutputRouterRegistry {
    @Volatile var activeEngine: MultiDeviceSyncEngine? = null

    fun onMediaProjectionReady(projection: MediaProjection) {
        activeEngine?.attachMediaProjection(projection)
    }

    fun onMediaProjectionStopped() {
        activeEngine?.detachMediaProjection()
    }
}
