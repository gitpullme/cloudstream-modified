package com.lagradost.cloudstream3.services

import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.lagradost.api.Log
import com.lagradost.cloudstream3.actions.ExternalPlaybackMediaSessionTracker
import com.lagradost.cloudstream3.actions.NetworkStreamPlayerLauncher

/**
 * Testable listener lifecycle used by [ExternalPlaybackNotificationListenerService].
 * Keeping this separate makes connect/disconnect/reconnect behavior deterministic
 * without instantiating an Android service.
 *
 * The coordinator owns the rebind contract: after disconnection it requests a
 * listener rebind through [requestRebind]. Tests supply a fake; production
 * delegates to [NotificationListenerService.requestRebind].
 */
class ExternalPlaybackListenerCoordinator(
    private val start: () -> Unit,
    private val stop: (String) -> Unit,
    private val requestScan: () -> Unit,
    private val targetPackage: String = NetworkStreamPlayerLauncher.PACKAGE_NAME,
    /**
     * Checks whether notification-listener access is still granted.
     * Production supplies [NotificationManagerCompat.getEnabledListenerPackages].
     */
    private val hasNotificationAccess: () -> Boolean = { true },
    /**
     * Requests an Android notification-listener rebind.
     * Production calls [NotificationListenerService.requestRebind].
     * Tests supply a recording lambda.
     */
    private val requestRebind: () -> Boolean = { false },
    private val diagnostics: (String) -> Unit = {},
) {
    /** Whether the last lifecycle event was a connect (true) or disconnect (false). */
    var connected: Boolean = false
        private set

    /** Number of rebind requests issued since creation. */
    var rebindRequestCount: Int = 0
        private set

    /** Whether the last rebind request succeeded (i.e. did not throw). */
    var lastRebindSucceeded: Boolean = false
        private set

    fun onConnected() {
        connected = true
        diagnostics("LISTENER_RECONNECTED: notification listener connected")
        start()
    }

    fun onDisconnected() {
        connected = false
        diagnostics("LISTENER_DISCONNECTED: notification listener disconnected")
        // Stop tracker operations but do NOT clear pending episode context.
        stop("listener-disconnected")

        // Request rebind only if notification access is still granted.
        if (hasNotificationAccess()) {
            rebindRequestCount++
            lastRebindSucceeded = try {
                requestRebind()
                diagnostics("REBIND_REQUESTED: notification listener rebind requested")
                true
            } catch (t: Throwable) {
                diagnostics("REBIND_REQUESTED: rebind request failed: ${t.message}")
                false
            }
        } else {
            diagnostics("REBIND_REQUESTED: skipped — notification access not granted")
            lastRebindSucceeded = false
        }
    }

    fun onNotification(packageName: String?) {
        if (packageName == targetPackage) requestScan()
    }

    fun onDestroy() {
        stop("service-destroyed")
    }
}

/**
 * Notification access is required by Android to read active MediaSessions.
 *
 * The service does not read notification content. It only uses the listener
 * connection as the permission gate for MediaSessionManager.getActiveSessions
 * and as a hint that Network Stream Player may have started playback.
 */
class ExternalPlaybackNotificationListenerService : NotificationListenerService() {
    private val coordinator by lazy {
        val component = ComponentName(
            applicationContext,
            ExternalPlaybackNotificationListenerService::class.java,
        )
        ExternalPlaybackListenerCoordinator(
            start = {
                Log.d(TAG, "Notification listener connected")
                ExternalPlaybackMediaSessionTracker.start(this)
            },
            stop = { reason ->
                Log.d(TAG, "Notification listener stop reason=$reason")
                ExternalPlaybackMediaSessionTracker.stop(reason)
            },
            requestScan = { ExternalPlaybackMediaSessionTracker.requestImmediateScan() },
            hasNotificationAccess = {
                try {
                    NotificationManagerCompat.getEnabledListenerPackages(applicationContext)
                        .contains(applicationContext.packageName)
                } catch (_: Throwable) {
                    false
                }
            },
            requestRebind = {
                // requestRebind is available from API 24 (N).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    requestRebind(component)
                    Log.d(TAG, "REBIND_REQUESTED: requestRebind called for $component")
                    true
                } else {
                    Log.w(TAG, "REBIND_REQUESTED: requestRebind unavailable below API 24")
                    false
                }
            },
            diagnostics = { Log.d(TAG, it) },
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        coordinator.onConnected()
    }

    override fun onListenerDisconnected() {
        coordinator.onDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        coordinator.onNotification(sbn?.packageName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        coordinator.onNotification(sbn?.packageName)
    }

    override fun onDestroy() {
        coordinator.onDestroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ExternalPlayback"
    }
}
