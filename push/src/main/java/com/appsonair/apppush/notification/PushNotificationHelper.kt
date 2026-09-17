package com.appsonair.apppush.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.LogLevel
import com.appsonair.apppush.PushNotification
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds and displays rich Android notifications.
 *
 * Called automatically by [com.appsonair.apppush.service.PushFirebaseMessagingService]
 * for data-only FCM messages received while the app is in the background.
 *
 * ## Supported FCM data payload keys
 *
 * ```json
 * {
 *   "data": {
 *     "title":      "Order shipped",
 *     "body":       "Your package is on the way.",
 *     "image_url":  "https://cdn.example.com/banner.jpg",
 *     "channel_id": "transactional",
 *     "sound":      "chime",
 *     "actions":    "[{\"id\":\"reply\",\"title\":\"Reply\"}]"
 *   }
 * }
 * ```
 *
 * | Key | Meaning |
 * |-----|---------|
 * | `title` | Title shown in the notification drawer. Pre-translated by the backend into the user's language based on the registered `deviceLocale`. Falls back to the `notification.title` from the FCM notification block if absent. |
 * | `body` | Body text shown in the notification drawer. Pre-translated by the backend. Falls back to `notification.body` if absent. |
 * | `image_url` | HTTPS URL of an image (JPEG/PNG) to download and display using BigPictureStyle. Falls back to BigTextStyle if absent or if the download fails. |
 * | `channel_id` | Android notification channel ID to post on. Defaults to [CHANNEL_ID] if absent. |
 * | `sound` | Name of a file in the host app's `res/raw` (without extension). Falls back to the default notification sound if absent or unresolvable. On Android 8+ a custom sound gets its own channel, since channel sound is immutable after creation. |
 * | `actions` | JSON array of `{"id","title"}` action buttons, max 3. Tapping one fires `INotificationClickListener` with `result.actionId` set and enqueues a `CLICKED` event. A payload carrying this key is always rendered by the SDK, notification block or not — see `PushFirebaseMessagingService.handleIntent()`. |
 * | `badge_count` | Integer (as a string) shown on the notification's long-press count on launchers that support it (e.g. Pixel). Ignored if absent or not a valid non-negative integer. The app-icon overlay badge is a separate mechanism — see [PushFirebaseMessagingService], which sets it from the same key. |
 *
 * ---
 *
 * ## Custom launch intent
 * Pass a custom [Intent] as [launchIntent] to deep-link into a specific screen
 * instead of the default launcher activity.
 */
object PushNotificationHelper {

    const val CHANNEL_ID   = "appsonair_push_channel"
    const val CHANNEL_NAME = "Push Notifications"

    // Firebase's own notification defaults. Reusing these keys means the host configures the
    // icon and tint once and both Firebase-rendered (notification-block) and SDK-rendered
    // (data-only) notifications look identical.
    private const val META_ICON  = "com.google.firebase.messaging.default_notification_icon"
    private const val META_COLOR = "com.google.firebase.messaging.default_notification_color"

    // Android shows at most three action buttons; extra entries are ignored.
    private const val MAX_ACTIONS = 3

    // Resolved once — buildAndNotify() runs per notification and these are PackageManager reads.
    @Volatile private var cachedSmallIcon = 0
    @Volatile private var cachedAccentColor = 0
    @Volatile private var iconFallbackLogged = false

    /**
     * Build and display a notification for [notification].
     *
     * **Threading**: Safe to call from any thread. When the notification carries an image,
     * the download blocks on network I/O — called from the main thread the download is moved
     * to a worker and the notification is posted once it completes; called from a background
     * thread (e.g. [com.appsonair.apppush.service.PushFirebaseMessagingService.onMessageReceived])
     * it runs inline, so the notification is posted before the service is torn down.
     *
     * @param context      Application or service context.
     * @param notification Parsed push notification (title, body, data map).
     * @param launchIntent Optional intent fired when the user taps the notification.
     *                     Defaults to the app's default launcher activity.
     * @param channelId    Notification channel ID to use. Defaults to [CHANNEL_ID].
     *                     Provide a custom channel created via
     *                     [PushNotifications.createNotificationChannel] or
     *                     [ensureChannel] for custom importance/sound.
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        notification: PushNotification,
        launchIntent: Intent? = null,
        channelId: String = CHANNEL_ID
    ) {
        // Prefer the resolved notification-block image; fall back to the image_url data key
        // for callers that build a PushNotification by hand.
        val imageUrl = notification.imageUrl ?: notification.data["image_url"]
        if (imageUrl.isNullOrBlank()) {
            buildAndNotify(context, notification, launchIntent, channelId, bitmap = null)
            return
        }
        // downloadBitmap() blocks on network I/O. On the main thread that is an immediate
        // NetworkOnMainThreadException — which is why the foreground push path never showed
        // an image — so hop to a worker there. Off the main thread (the FCM background path)
        // download inline, so the notification is posted before the service is torn down.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread(
                { buildAndNotify(context, notification, launchIntent, channelId, downloadBitmap(imageUrl)) },
                "aoa-notif-image"
            ).start()
        } else {
            buildAndNotify(context, notification, launchIntent, channelId, downloadBitmap(imageUrl))
        }
    }

    // Builds and posts the notification. [bitmap] is the already-downloaded image, or null
    // when the payload carried none or the download failed.
    private fun buildAndNotify(
        context: Context,
        notification: PushNotification,
        launchIntent: Intent?,
        channelId: String,
        bitmap: Bitmap?
    ) {
        // sound — names a file in the host app's res/raw (e.g. "chime" -> res/raw/chime.mp3).
        // Missing key or missing resource both fall through to the channel's default sound.
        val soundName = notification.sound ?: notification.data["sound"]
        val soundUri  = resolveSoundUri(context, soundName)

        // On Android 8+ the sound belongs to the channel, not the notification —
        // builder.setSound() is ignored there, and a channel's sound cannot be changed after
        // it is created. So a custom sound needs a channel of its own, keyed by sound name.
        val useChannelSound = soundUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val effectiveChannelId =
            if (useChannelSound) "${channelId}_snd_$soundName" else channelId
        ensureChannel(
            context,
            effectiveChannelId,
            if (useChannelSound) "$CHANNEL_NAME ($soundName)" else CHANNEL_NAME,
            soundUri.takeIf { useChannelSound }
        )

        // Derive a stable notification ID from the push ID (or timestamp as fallback).
        val notifId = notification.id?.hashCode() ?: System.currentTimeMillis().toInt()

        // badge_count — drives the OS-native notification dot's long-press count on launchers
        // that support it (e.g. Pixel). Distinct from the app-icon overlay badge, which
        // PushFirebaseMessagingService sets separately from the same data key via
        // AppPushService.setBadgeCount() — that one is a device-wide OEM call, not tied to
        // building a single notification, and also needed when this helper is invoked directly
        // (e.g. PushNotificationHelper.show() from host code) without going through FCM.
        //
        // This helper always treats the value as an already-resolved absolute count — it does
        // NOT know about "badge_type": "increase". PushFirebaseMessagingService resolves
        // "increase" against the persisted baseline (AppPushService.resolveBadgeCount()) and
        // overwrites this data key with the resolved absolute number before calling show(), so
        // the two badge mechanisms above never disagree. A direct show() call from host code
        // (bypassing FCM) is always treated as "set" — pass the final number you want displayed.
        val badgeCount = notification.data["badge_count"]?.toIntOrNull()?.takeIf { it >= 0 }

        val intent = (launchIntent
            ?: context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    // addFlags, never `flags =`: getLaunchIntentForPackage() already sets
                    // FLAG_ACTIVITY_NEW_TASK, which is required to start an activity from a
                    // notification. Assigning over it made a tap silently do nothing while the
                    // app was backgrounded — a task existed but could not be brought forward —
                    // and only appear to work from a killed state, where there was no task and
                    // the system created one.
                    //
                    // CLEAR_TOP + SINGLE_TOP then deliver this intent, with its extras, to the
                    // existing activity via onNewIntent() instead of starting a second copy.
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                })
            ?.apply {
                // Stamp notification data onto the intent so cold-start taps can be
                // handled by AppPushService.handleNotificationTapIntent() in MainActivity.
                putExtra("notification_id", notification.id)
                putExtra("title", notification.title)
                putExtra("body", notification.body)
                notification.data.forEach { (k, v) -> putExtra(k, v) }
                // After the data loop, so the resolved URL (notification block wins over the
                // data key) is what handleNotificationTapIntent() reads back.
                putExtra("image_url", notification.imageUrl ?: notification.data["image_url"])
            }

        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context,
                notifId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(context, effectiveChannelId)
            // Must be an alpha-only silhouette; the launcher icon fallback renders as a
            // white square. See resolveSmallIcon().
            .setSmallIcon(resolveSmallIcon(context))
            // title — pre-translated title from the backend (server-side localisation).
            // Falls back to the FCM notification block title if the data key is absent.
            .setContentTitle(notification.title)
            // body — pre-translated body from the backend (server-side localisation).
            // Falls back to the FCM notification block body if the data key is absent.
            .setContentText(notification.body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply {
                pendingIntent?.let { setContentIntent(it) }
                resolveAccentColor(context).takeIf { it != 0 }?.let { setColor(it) }
                // Pre-O the sound rides on the notification; O+ takes it from the channel above.
                if (soundUri != null && !useChannelSound) setSound(soundUri)
                badgeCount?.let { setNumber(it) }
            }

        // actions — optional data key carrying notification action buttons. Each button
        // re-launches the same intent with an "action_id" extra, which
        // AppPushService.handleNotificationTapIntent() reads to emit CLICKED instead of OPENED.
        addActions(context, builder, notification, intent, notifId)

        // Downloaded by show(). Null when the payload carried no image, or the fetch failed —
        // downloadBitmap() logs the reason. Either way, fall back to expandable text.
        if (bitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .setBigContentTitle(notification.title)
                    .setSummaryText(notification.body)
            )
            // Show the image as a large icon in the collapsed notification too.
            builder.setLargeIcon(bitmap)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // NotificationManager.notify() is a silent no-op when notifications are disabled for
        // the app — no exception, no system log — so an integration looks identical to a
        // delivery failure. WARN rather than ERROR: the user simply having notifications
        // switched off is a normal state, not a broken integration, and this runs on every push.
        if (!AppPushService.isPermissionGranted(context)) {
            AppPushService.log(
                "[NotificationHelper] Notifications are disabled for this app — the system will " +
                "discard this notification (id=$notifId). Request POST_NOTIFICATIONS on Android 13+, " +
                "or check Settings if the user turned notifications off.",
                LogLevel.WARN
            )
        }

        // collapse_key — Scope §3.14: if set, a newer notification with the same key replaces
        // the previous one in the notification tray instead of stacking.
        // Backend sets this in the FCM data payload: { "data": { "collapse_key": "order_update" } }
        // On Android, NotificationManager.notify(tag, id, notification) uses tag as the collapse key.
        val collapseKey = notification.data["collapse_key"]
        if (collapseKey != null) {
            manager.notify(collapseKey, notifId, builder.build())
            AppPushService.log("[NotificationHelper] Showed notification with collapse_key=$collapseKey. id=$notifId")
        } else {
            manager.notify(notifId, builder.build())
        }
    }

    /**
     * Create the notification channel on Android 8.0+ (O+).
     * No-op on earlier API levels or if the channel already exists.
     * Uses [CHANNEL_ID] and [CHANNEL_NAME] when called with no arguments.
     */
    @JvmStatic
    @JvmOverloads
    fun ensureChannel(
        context: Context,
        channelId: String = CHANNEL_ID,
        channelName: String = CHANNEL_NAME,
        soundUri: Uri? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Deliberate: a channel's sound and importance are immutable once created, which is
        // exactly why a custom sound gets its own channel ID rather than mutating this one.
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AppsOnAir push notifications"
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
    }

    /**
     * Small-icon resource, from the Firebase `default_notification_icon` meta-data.
     *
     * Falls back to the launcher icon when unset — which Android renders as a white square,
     * since small icons must be alpha-only silhouettes. That fallback is logged once so the
     * blob is diagnosable rather than silent.
     */
    private fun resolveSmallIcon(context: Context): Int {
        cachedSmallIcon.takeIf { it != 0 }?.let { return it }
        val fromMeta = appMetaData(context)?.getInt(META_ICON, 0) ?: 0
        val resolved = if (fromMeta != 0) fromMeta else {
            if (!iconFallbackLogged) {
                iconFallbackLogged = true
                AppPushService.log(
                    "[NotificationHelper] No \"$META_ICON\" meta-data — falling back to the " +
                    "launcher icon, which Android renders as a white square. Declare a " +
                    "silhouette drawable via that meta-data in your AndroidManifest.",
                    LogLevel.WARN
                )
            }
            context.applicationInfo.icon
        }
        cachedSmallIcon = resolved
        return resolved
    }

    /** Accent colour from the Firebase `default_notification_color` meta-data; 0 when unset. */
    private fun resolveAccentColor(context: Context): Int {
        if (cachedAccentColor != 0) return cachedAccentColor
        val resId = appMetaData(context)?.getInt(META_COLOR, 0) ?: 0
        val color = if (resId == 0) 0
                    else runCatching { ContextCompat.getColor(context, resId) }.getOrDefault(0)
        cachedAccentColor = color
        return color
    }

    private fun appMetaData(context: Context): Bundle? = runCatching {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
    }.getOrNull()

    /**
     * Resolve the "sound" data key to a res/raw URI.
     * Returns null when the key is absent, and logs a warning and returns null when the
     * resource does not exist — the caller then falls through to the default sound.
     */
    internal fun resolveSoundUri(context: Context, name: String?): Uri? {
        if (name.isNullOrBlank()) return null
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) {
            AppPushService.log(
                "[NotificationHelper] sound=\"$name\" not found in res/raw — " +
                "using the default notification sound.",
                LogLevel.WARN
            )
            return null
        }
        return Uri.parse("android.resource://${context.packageName}/raw/$name")
    }

    /**
     * Add action buttons declared in the "actions" data key:
     * `{"data": {"actions": "[{\"id\":\"reply\",\"title\":\"Reply\"}]"}}`
     *
     * Only applies to notifications this SDK builds — but that now includes notification-block
     * pushes that declare this key, which PushFirebaseMessagingService.handleIntent() takes
     * from Firebase's renderer precisely because that renderer knows nothing about it.
     */
    private fun addActions(
        context: Context,
        builder: NotificationCompat.Builder,
        notification: PushNotification,
        baseIntent: Intent?,
        notifId: Int
    ) {
        val raw = notification.data["actions"]
        if (raw.isNullOrBlank() || baseIntent == null) return

        val array = runCatching { JSONArray(raw) }.getOrElse {
            AppPushService.log(
                "[NotificationHelper] \"actions\" is not valid JSON — no buttons added: ${it.message}",
                LogLevel.WARN
            )
            return
        }

        var added = 0
        for (i in 0 until array.length()) {
            if (added == MAX_ACTIONS) break
            val obj   = array.optJSONObject(i) ?: continue
            val id    = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: continue

            val actionIntent = Intent(baseIntent).putExtra("action_id", id)
            val pendingIntent = PendingIntent.getActivity(
                context,
                // Must differ from the content intent's requestCode (notifId) — reusing it
                // would overwrite that PendingIntent and every button would act as a body tap.
                notifId * 31 + i + 1,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Icon 0 — text-only action, which is the standard presentation on API 24+.
            builder.addAction(0, title, pendingIntent)
            added++
        }
    }

    // Download a Bitmap from a URL on the calling thread.
    // Returns null if the download fails for any reason (no crash) — every failure path is
    // logged, so "no image key sent" and "image fetch failed" are distinguishable in Logcat.
    private fun downloadBitmap(urlString: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000   // 5 s connect timeout
                readTimeout    = 10_000  // 10 s read timeout
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                AppPushService.log(
                    "[NotificationHelper] Image fetch failed: HTTP $status for $urlString",
                    LogLevel.WARN
                )
                return null
            }
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                AppPushService.log(
                    "[NotificationHelper] Image decoded to null (not a valid image?): $urlString",
                    LogLevel.WARN
                )
            } else {
                AppPushService.log(
                    "[NotificationHelper] Image loaded (${bitmap.width}x${bitmap.height}): $urlString"
                )
            }
            bitmap
        } catch (t: Throwable) {
            // Catches NetworkOnMainThreadException, timeouts, DNS/TLS failures, cleartext
            // blocks, non-HTTP URLs (ClassCastException) and OutOfMemoryError on huge images.
            AppPushService.log(
                "[NotificationHelper] Image fetch failed for $urlString",
                LogLevel.WARN,
                t
            )
            null
        } finally {
            connection?.disconnect()
        }
    }
}
