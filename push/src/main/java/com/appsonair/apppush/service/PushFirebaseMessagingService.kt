package com.appsonair.apppush.service

import android.content.Intent
import android.os.Bundle
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

    /**
     * firebase-messaging draws a notification-block message itself when the app is
     * backgrounded and never calls [onMessageReceived] — and its renderer has no concept of
     * action buttons, because FCM has no payload field for them. So the buttons declared in
     * the "actions" data key silently disappear on every background delivery.
     *
     * Take those messages before that branch and hand them to [onMessageReceived], which
     * renders through PushNotificationHelper and does know about "actions". Nothing is
     * stripped from the intent, so RemoteMessage still exposes the notification block through
     * getNotification() — title, body, image, and channel survive the handover.
     *
     * Deliberately narrow: every other message keeps Firebase's own path, including the
     * notification_receive analytics that super.handleIntent() logs and this does not.
     */
    override fun handleIntent(intent: Intent) {
        val extras = intent.extras
        if (extras != null && shouldIntercept(extras)) {
            AppPushService.log(
                "Notification-block push from AppsOnAir (or declaring \"actions\") — rendering it " +
                    "in the SDK so it matches the foreground path.",
                LogLevel.DEBUG
            )
            onMessageReceived(RemoteMessage(extras))
            return
        }

        super.handleIntent(intent)
    }

    /**
     * True for a notification block Firebase would render wrongly: one that declares action
     * buttons, or any AppsOnAir push (it carries "notification_id"). Firebase's renderer
     * knows nothing of "group", the SDK's channel/icon/big-picture handling or the app-icon
     * badge, so letting it draw an AppsOnAir push makes the same payload look different in
     * the background than in the foreground. A host app's own notification-block pushes have
     * no "notification_id" and keep Firebase's path.
     *
     * The block reaches the service as "gcm.n.*" extras ("gcm.notification.*" from older
     * senders); data keys arrive as plain extras alongside them.
     *
     * internal rather than private because it is the whole decision this override makes, and
     * the branch it guards cannot be observed under Robolectric — Firebase only draws the
     * notification itself when the app is backgrounded, which a unit test cannot stage.
     */
    internal fun shouldIntercept(extras: Bundle): Boolean =
        (!extras.getString("actions").isNullOrBlank() || !extras.getString("notification_id").isNullOrBlank()) &&
            extras.keySet().any { it.startsWith("gcm.n.") || it.startsWith("gcm.notification.") }

    /**
     * The data map plus the display keys the backend may send only in the notification
     * block, so PushNotificationHelper and the badge code read one place. Data keys win.
     *
     * - "visibility": android.notification.visibility arrives as an Int (-1/0/1).
     * - "badge_count": android.notification.notification_count.
     *
     * Collapse is not handled here: android.collapse_key is applied by FCM itself.
     */
    internal fun withNotificationBlockFallbacks(message: RemoteMessage): Map<String, String> {
        val block = message.notification
        val visibility = block?.visibility?.let {
            when (it) { -1 -> "secret"; 0 -> "private"; else -> "public" }
        }
        val badgeCount = block?.notificationCount?.toString()
        if (visibility == null && badgeCount == null) return message.data
        return message.data.toMutableMap().apply {
            visibility?.let { putIfAbsent("visibility", it) }
            badgeCount?.let { putIfAbsent("badge_count", it) }
        }
    }

    // Called when FCM delivers a message.
    // Notification-payload + background → Firebase shows the notification; this is NOT called,
    //   unless it is an AppsOnAir push or declares "actions" and handleIntent() above hands it over.
    // Data-only payload (any state) OR notification-payload + foreground → this IS called.
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // A killed app woken by this push may never have run initialize() — wrappers call it
        // from JS/Dart, which doesn't start for a background push. Restore what the delivered
        // event needs so it isn't lost.
        AppPushService.restoreStateForBackgroundDelivery(applicationContext)

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

        // Same notification through a second token (a device migrated from another push
        // provider can be briefly reachable via its imported token and its new one): distinct
        // FCM messages, so the messageId check above misses it. Before the silent branch, so a
        // silent push isn't dispatched twice either.
        val notificationId = message.data["notification_id"]
        if (notificationId != null &&
            !RecentNotificationIds.markIfNew(AppPushService.storage.prefs, notificationId)
        ) {
            AppPushService.log(
                "Duplicate notification ignored (already handled): notification_id=$notificationId",
                LogLevel.INFO
            )
            return
        }

        // Returns before any badge or display work. "silent" is a data key by necessity —
        // FCM has no transport-level equivalent of APNs' content-available.
        if (message.data["silent"] == "true") {
            AppPushService.dispatchSilentPush(
                message.data["notification_id"] ?: message.messageId,
                message.data
            )
            return
        }

        // Resolved once, up front, so the app-icon badge and the notification-dot count below
        // agree on the same absolute number.
        val data = withNotificationBlockFallbacks(message)
        val resolvedBadgeCount = AppPushService.parseBadgePayload(data)?.let { (rawCount, badgeType) ->
            AppPushService.resolveBadgeCount(rawCount, badgeType)
        }
        val notificationData = if (resolvedBadgeCount != null) {
            data.toMutableMap().apply { put("badge_count", resolvedBadgeCount.toString()) }
        } else {
            data
        }

        // Build PushNotification model from FCM message.
        // Supports both notification payload and data-only payload.
        // "android.notification.channel_id" arrives as RemoteMessage.Notification.channelId,
        // which is the only place it exists for the notification-block pushes handleIntent()
        // hands over — it is not copied into the data map.
        val channelId = message.data["channel_id"]
            ?: message.notification?.channelId
            ?: PushNotificationHelper.CHANNEL_ID
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
        // NOTE: no effect on non-AppsOnAir notification-block payloads received while
        // backgrounded — Firebase draws those itself and never calls this method.
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
            // Backgrounded push Firebase did not draw: either data-only, or a notification
            // block that handleIntent() intercepted. The SDK renders it here.
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
