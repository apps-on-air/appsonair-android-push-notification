package com.appsonair.push

interface PushListener {
    /** Called when FCM issues or refreshes the device token. Send this to your backend. */
    fun onTokenUpdated(token: String) {}

    /** Called when Firebase Installation ID is available. Forward-compatible with future FCM changes. */
    fun onInstallationIdUpdated(id: String) {}

    /** Called when a push notification is received while the app is in the foreground. */
    fun onNotificationReceived(notification: PushNotification) {}

    /** Called when the user taps a notification. */
    fun onNotificationOpened(notification: PushNotification) {}

    /** Called on any SDK error (e.g. token fetch failed, not initialized). */
    fun onError(error: PushError) {}
}

/** Data from a received or tapped notification. */
data class PushNotification(
    /** Value of "notification_id" from the FCM payload. Null if not present. */
    val id: String?,
    val title: String?,
    val body: String?,
    /** Full FCM data payload — access any custom keys sent from your backend. */
    val data: Map<String, String>,
    /**
     * Image to display with the notification. Resolved from the FCM notification block
     * (`notification.image` / `android.notification.image`), falling back to the
     * `image_url` data key. Null when the payload carries neither.
     */
    val imageUrl: String? = null,
    /**
     * Sound to play, as a file name in the host app's `res/raw` (no extension). Resolved from
     * the FCM notification block (`android.notification.sound`), falling back to the `sound`
     * data key. Null when the payload carries neither.
     */
    val sound: String? = null
)

data class PushError(
    val code: Code,
    val message: String,
    val cause: Throwable? = null
) {
    enum class Code {
        NOT_INITIALIZED,
        PERMISSION_DENIED,
        /** No google-services.json / google-services plugin — no Firebase APIs can be used. */
        FIREBASE_NOT_CONFIGURED,
        TOKEN_FETCH_FAILED,
        INSTALLATION_ID_FETCH_FAILED,
        UNKNOWN
    }
}

enum class LogLevel { NONE, FATAL, ERROR, WARN, INFO, DEBUG, VERBOSE }

/**
 * Foreground lifecycle listener event.
 * Call event.preventDefault() to suppress system notification display.
 */
class NotificationWillDisplayEvent(val notification: PushNotification) {
    private var prevented = false
    val isPreventDefault: Boolean get() = prevented
    fun preventDefault() { prevented = true }
}

/**
 * Details of how the user interacted with the notification.
 */
data class NotificationClickResult(
    /** null = notification body tapped; non-null = specific action button ID */
    val actionId: String?,
    /** Launch URL attached to the notification payload ("url" key), if any. */
    val url: String?
)

/**
 * Passed to click listeners on notification tap.
 */
data class NotificationClickEvent(
    val notification: PushNotification,
    /** What the user tapped — action button ID and/or launch URL. */
    val result: NotificationClickResult
)

data class PushSubscriptionState(
    val token: String?,
    val optedIn: Boolean
)

data class PushSubscriptionChangedState(
    val previous: PushSubscriptionState,
    val current: PushSubscriptionState
)

/** Snapshot of the user's identity. */
data class UserState(
    /** The external user ID linked via login(). null when anonymous. */
    val externalId: String?,
    /** The AppsOnAir-assigned device ID. */
    val appsOnAirId: String
)

/**
 * Passed to user-state observers. Access the values via `state.current`.
 */
data class UserChangedState(
    val current: UserState
)

interface INotificationLifecycleListener {
    fun onWillDisplay(event: NotificationWillDisplayEvent)
}

interface INotificationClickListener {
    fun onClick(event: NotificationClickEvent)
}

interface INotificationPermissionObserver {
    fun onNotificationPermissionDidChange(permission: Boolean)
}

interface IPushSubscriptionObserver {
    fun onPushSubscriptionDidChange(state: PushSubscriptionChangedState)
}

interface IUserStateObserver {
    fun onUserStateDidChange(state: UserChangedState)
}

enum class PushEventType {
    /** User tapped the notification body (actionId is null). */
    OPENED,
    /** User tapped a specific action button (actionId is set). */
    CLICKED,
    /** Notification delivered to the foreground (local record — no backend call for free tier). */
    RECEIVED,
    /** Confirmed delivery to the device — powers "Delivered" analytics (paid tier). */
    DELIVERED
}

data class PushEvent(
    /** What happened. */
    val type: PushEventType,
    /** AppsOnAir notification ID from the FCM payload ("notification_id" key). Null if not present. */
    val notificationId: String? = null,
    /** Backend-assigned subscription ID for this device. Null until /subscriptions API is called. */
    val subscriptionId: String? = null,
    val actionId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = ""
)
