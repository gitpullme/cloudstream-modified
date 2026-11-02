package com.lagradost.cloudstream3.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lagradost.api.Log
import com.lagradost.cloudstream3.actions.ExternalPlaybackMediaSessionTracker
import com.lagradost.cloudstream3.actions.NetworkStreamPlayerLauncher

/**
 * Testable listener lifecycle used by [ExternalPlaybackNotificationListenerService].
 * Keeping this separate makes connect/disconnect/reconnect behavior deterministic
 * without instantiating an Android service.
 */
class ExternalPlaybackListenerCoordinator(
    private val start: () -> Unit,
    private val stop: (String) -> Unit,
    private val requestScan: () -> Unit,
    private val targetPackage: String = NetworkStreamPlayerLauncher.PACKAGE_NAME,
) {
    fun onConnected() {
        start()
    }

    fun onDisconnected() {
        stop("listener-disconnected")
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
        ExternalPlaybackListenerCoordinator(
            start = {
                Log.d("ExternalPlayback", "Notification listener connected")
                ExternalPlaybackMediaSessionTracker.start(this)
            },
            stop = { reason ->
                Log.d("ExternalPlayback", "Notification listener stop reason=$reason")
                ExternalPlaybackMediaSessionTracker.stop(reason)
            },
            requestScan = { ExternalPlaybackMediaSessionTracker.requestImmediateScan() },
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
}
