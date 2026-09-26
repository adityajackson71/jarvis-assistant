package com.example.jarvis

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationReaderService : NotificationListenerService() {

    companion object {
        val recentNotifications = mutableListOf<String>()
        private const val MAX_STORED = 20
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isNotBlank() || text.isNotBlank()) {
            val entry = "[${sbn.packageName}] $title: $text"
            recentNotifications.add(0, entry)
            if (recentNotifications.size > MAX_STORED) {
                recentNotifications.removeAt(recentNotifications.size - 1)
            }
        }
    }
}
