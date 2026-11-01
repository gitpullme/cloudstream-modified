package com.lagradost.cloudstream3.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.lagradost.cloudstream3.actions.ExternalPlaybackMediaSessionTracker
import com.lagradost.cloudstream3.actions.NetworkStreamPlayerLauncher

/**
 * Notification access is required by Android to read active MediaSessions.
 *
 * The service does not read notification content. It only uses the listener
 * connection as the permission gate for MediaSessionManager.getActiveSessions
 * and as a hint that Network Stream Player may have started playback.
 */
class ExternalPlaybackNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        ExternalPlaybackMediaSessionTracker.start(this)
    }

    override fun onListenerDisconnected() {
        ExternalPlaybackMediaSessionTracker.stop()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == NetworkStreamPlayerLauncher.PACKAGE_NAME) {
            ExternalPlaybackMediaSessionTracker.requestImmediateScan()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == NetworkStreamPlayerLauncher.PACKAGE_NAME) {
            ExternalPlaybackMediaSessionTracker.requestImmediateScan()
        }
    }

    override fun onDestroy() {
        ExternalPlaybackMediaSessionTracker.stop()
        super.onDestroy()
    }
}
