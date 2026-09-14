package com.appsonair.apppush.service

import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.PushSessionManager
import com.appsonair.apppush.LogLevel
import com.appsonair.apppush.NotificationWillDisplayEvent
import com.appsonair.apppush.PushNotification
import com.appsonair.apppush.notification.PushNotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushFirebaseMessagingService : FirebaseMessagingService() {

    /** Firebase hands us the rotated token directly — persist that one, don't re-fetch. */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        AppPushService.handleRotatedToken(token)
    }

    // Called when FCM delivers a message.
    // Notification-payload + background → Firebase shows the notification; this is NOT called.
    // Data-only payload (any state) OR notification-payload + foreground → this IS called.
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // FCM is at-least-once: the same message can legitimately arrive twice. Unguarded
        // that double-counts the "increase" badge baseline and the event queue, and posts the
        // notification twice. In-memory is enough — duplicates arrive within seconds.
        val messageId = message.messageId
        if (messageId != null && !DedupCache.markSeenIfNew(messageId)) {
            AppPushService.log("Duplicate FCM message ignored (already processed): id=$messageId")
            return
        }
        // At INFO: tells a Logcat trace whether a pass came from a distinct messageId or the
        // same one slipping past the dedup above.
        AppPushService.log(
            "Processing FCM message: messageId=$messageId notification_id=${message.data["notification_id"]}",
            LogLevel.INFO
        )

        // Returns before any badge or display work. "silent" is a data key by necessity —
        // FCM has no transport-level equivalent of APNs' content-available.
        if (message.data["silent"] == "true") {
            AppPushService.dispatchSilentPush(message.data)
            return
        }

        // Resolved once, up front, so the app-icon badge and the notification-dot count below
        // agree on the same absolute number.
        val resolvedBadgeCount = AppPushService.parseBadgePayload(message.data)?.let { (rawCount, badgeType) ->
            AppPushService.resolveBadgeCount(rawCount, badgeType)
        }
        val notificationData = if (resolvedBadgeCount != null) {
            message.data.toMutableMap().apply { put("badge_count", resolvedBadgeCount.toString()) }
        } else {
            message.data
        }

        // Build PushNotification model from FCM message.
        // Supports both notification payload and data-only payload.
        val channelId = message.data["channel_id"] ?: PushNotificationHelper.CHANNEL_ID
        val notification = PushNotification(
            id    = message.data["notification_id"] ?: message.messageId,
            title = message.notification?.title ?: message.data["title"],
            body  = message.notification?.body  ?: message.data["body"],
            data  = notificationData,
            // Standard FCM notification-block image (payload "notification.image" or
            // "android.notification.image") arrives as RemoteMessage.Notification.imageUrl.
            // Falls back to the "image_url" data key for data-only payloads.
            imageUrl = message.notification?.imageUrl?.toString() ?: message.data["image_url"],
            // "android.notification.sound" arrives as RemoteMessage.Notification.sound.
            // Falls back to the "sound" data key for data-only payloads.
            // NOTE: Firebase itself ignores this field on Android 8+, where sound is a
            // property of the channel — it only takes effect on notifications the SDK
            // renders (foreground, or data-only payloads).
            sound = message.notification?.sound ?: message.data["sound"]
        )

        // App-icon badge (OEM-specific). Here rather than in PushNotificationHelper
        // because it is device-wide, so a manual show() outside FCM must not fire it.
        // NOTE: no effect on notification-block payloads received while backgrounded —
        // Firebase draws those itself and never calls this method.
        resolvedBadgeCount?.let { AppPushService.setBadgeCount(applicationContext, it) }

        if (PushSessionManager.isForeground) {
            // Listeners can call preventDefault() to suppress display.
            val event = NotificationWillDisplayEvent(notification)
            Handler(Looper.getMainLooper()).post {
                AppPushService.foregroundListeners.forEach { it.onWillDisplay(event) }
            }
            AppPushService.dispatchNotification(notification)
            // Second post, so a preventDefault() from the first has already run.
            Handler(Looper.getMainLooper()).post {
                if (!event.isPreventDefault) {
                    PushNotificationHelper.show(applicationContext, notification, channelId = channelId)
                }
            }
        } else {
            // Backgrounded data-only push: no notification block, so Firebase drew nothing
            // and the SDK renders it here.
            AppPushService.log("Notification received in background: id=${notification.id}. Displaying.")
            AppPushService.dispatchNotification(notification)
            PushNotificationHelper.show(applicationContext, notification, channelId = channelId)
        }
    }
}

private object DedupCache {
    private const val MAX_SIZE = 50
    private val seen = LruCache<String, Boolean>(MAX_SIZE)

    /** Returns true and records [id] if it hasn't been seen before; false if it's a duplicate. */
    @Synchronized
    fun markSeenIfNew(id: String): Boolean {
        if (seen.get(id) != null) return false
        seen.put(id, true)
        return true
    }
}
