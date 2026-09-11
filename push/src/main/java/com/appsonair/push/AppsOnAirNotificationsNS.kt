package com.appsonair.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.appsonair.push.notification.AppsOnAirNotificationHelper

object AppsOnAirNotificationsNS {

    /** Current notification permission status. */
    @JvmStatic
    fun permission(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether the SDK can still request notification permission via the system dialog.
     * Returns false once permission is granted, once the user has permanently denied it, and on
     * Android 12 and below where no runtime permission exists. In the permanently-denied case use
     * requestPermission(fallbackToSettings = true) to send the user to Settings instead.
     *
     * Accepts any Context. shouldShowRequestPermissionRationale() needs an Activity, so when the
     * caller passes an application Context the SDK uses the foreground Activity it already tracks
     * for notification taps. Only if no Activity has resumed yet — calling this from
     * Application.onCreate, say — does the permanently-denied case become indistinguishable, and
     * the answer conservatively falls back to false.
     */
    @JvmStatic
    fun canRequestPermission(context: Context): Boolean {
        // Below Android 13 there is no runtime permission and no dialog to show, so there is
        // nothing to request. iOS reports false for every status other than "not determined".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return false

        // Never asked — the system dialog will show.
        if (!AppsOnAirPush.hasRequestedPermission) return true

        // Asked before: Android keeps showing the dialog while it still offers a rationale.
        // Once it stops, the denial is permanent and only Settings can change it.
        val activity = context as? android.app.Activity
            ?: AppsOnAirPush.currentActivity
            ?: return false
        return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            android.Manifest.permission.POST_NOTIFICATIONS
        )
    }

    /** Request notification permission. On Android 13+ shows the system dialog. */
    @JvmStatic
    @JvmOverloads
    fun requestPermission(activity: android.app.Activity, fallbackToSettings: Boolean = false) {
        if (fallbackToSettings && !permission(activity)) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
            }
            activity.startActivity(intent)
        } else {
            AppsOnAirPush.requestNotificationPermission(activity)
        }
    }

    @JvmStatic
    fun addPermissionObserver(observer: INotificationPermissionObserver) {
        AppsOnAirPush.permissionObservers.add(observer)
    }

    @JvmStatic
    fun removePermissionObserver(observer: INotificationPermissionObserver) {
        AppsOnAirPush.permissionObservers.remove(observer)
    }

    
    @JvmStatic
    fun addForegroundLifecycleListener(listener: INotificationLifecycleListener) {
        AppsOnAirPush.foregroundListeners.add(listener)
    }

    @JvmStatic
    fun removeForegroundLifecycleListener(listener: INotificationLifecycleListener) {
        AppsOnAirPush.foregroundListeners.remove(listener)
    }

    
    @JvmStatic
    fun addClickListener(listener: INotificationClickListener) {
        AppsOnAirPush.clickListeners.add(listener)
    }

    @JvmStatic
    fun removeClickListener(listener: INotificationClickListener) {
        AppsOnAirPush.clickListeners.remove(listener)
    }

    @JvmStatic
    @JvmOverloads
    fun createNotificationChannel(
        context: Context,
        id: String,
        name: String,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        description: String = "",
        sound: String? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(id) != null) return // already exists
        val soundUri = AppsOnAirNotificationHelper.resolveSoundUri(context, sound)
        val channel = NotificationChannel(id, name, importance).apply {
            if (description.isNotEmpty()) this.description = description
            soundUri?.let {
                setSound(
                    it,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
        }
        manager.createNotificationChannel(channel)
        AppsOnAirPush.log(
            "Notification channel created: $id" + if (soundUri != null) " sound=$sound" else ""
        )
    }

    /** Delete a notification channel by ID. */
    @JvmStatic
    fun deleteNotificationChannel(context: Context, id: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel(id)
    }

    /**
     * Remove all delivered notifications from the notification shade.
     */
    @JvmStatic
    fun clearAllNotifications(context: Context) {
        AppsOnAirPush.clearAllNotifications(context)
    }

    @JvmStatic
    fun removeNotification(context: Context, notificationId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // AppsOnAirNotificationHelper.show() posts under notificationId.hashCode().
        val androidId = notificationId.hashCode()
        // Notifications carrying a "collapse_key" are posted with that key as the tag,
        // and cancel(id) never matches a tagged notification — find the tag first.
        val tag = manager.activeNotifications.firstOrNull { it.id == androidId }?.tag
        if (tag != null) manager.cancel(tag, androidId) else manager.cancel(androidId)
        AppsOnAirPush.log(
            "Notification removed: notificationId=$notificationId " +
            "androidId=$androidId tag=${tag ?: "none"}"
        )
    }

    /** Remove all notifications in a group. */
    @JvmStatic
    fun removeGroupedNotifications(context: Context, groupKey: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.activeNotifications
            .filter { it.groupKey?.contains(groupKey) == true }
            .forEach { manager.cancel(it.id) }
    }
}
