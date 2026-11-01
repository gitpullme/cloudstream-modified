package com.lagradost.cloudstream3.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.LocalStreamProxyServer
import com.lagradost.cloudstream3.utils.AppContextUtils.createNotificationChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps [LocalStreamProxyServer] alive while an external player is in the
 * foreground. The service is independent of any Activity lifecycle and is only
 * stopped when the last playback session expires or the player returns.
 */
class LocalStreamProxyService : Service() {
    companion object {
        const val TAG = "LocalStreamProxyService"
        const val CHANNEL_ID = "cloudstream3.local.proxy"
        const val NOTIFICATION_ID = 917194233

        private val isRunning = AtomicBoolean(false)

        /**
         * Starts the relay service. Must be called while the app is visible (the
         * launcher is invoked from the result screen), which satisfies Android's
         * background foreground-service start restrictions.
         */
        fun start(context: Context): Boolean {
            if (isRunning.get()) {
                Log.d(TAG, "Service already running")
                return true
            }
            return try {
                val intent = Intent(context, LocalStreamProxyService::class.java)
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start foreground relay service: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, LocalStreamProxyService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to stop relay service: ${t.message}")
            }
        }
    }

    private var cleanupExecutor: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)
        createNotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_proxy_notification_channel_name),
            getString(R.string.local_proxy_notification_channel_description)
        )

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }

        try {
            LocalStreamProxyServer.ensureStarted()
            Log.d(TAG, "Foreground relay active")
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start relay server from service: ${t.message}")
        }

        cleanupExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LocalStreamProxyServiceCleanup").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                {
                    try {
                        LocalStreamProxyServer.cleanupExpiredSessions()
                        if (!LocalStreamProxyServer.hasSessions()) {
                            Log.d(TAG, "No playback sessions remain, stopping relay service")
                            LocalStreamProxyServer.shutdownIfIdle()
                            stopSelf()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Relay cleanup failed: ${t.message}")
                    }
                },
                30L,
                30L,
                TimeUnit.SECONDS
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        cleanupExecutor?.shutdownNow()
        cleanupExecutor = null
        // Never close an active session here. If the process is being torn down,
        // the socket closes with it; if only the service is stopped, an active
        // transfer can still finish.
        val stopped = LocalStreamProxyServer.shutdownIfIdle()
        Log.d(TAG, "Relay service destroyed (serverStopped=$stopped)")
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntentCompat.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            0,
            false
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloudstream_monochrome)
            .setContentTitle(getString(R.string.local_proxy_notification_title))
            .setContentText(getString(R.string.local_proxy_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
