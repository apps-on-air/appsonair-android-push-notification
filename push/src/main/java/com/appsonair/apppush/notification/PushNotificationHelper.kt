package com.appsonair.apppush.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
 *     "title":            "Order shipped",
 *     "body":             "Your package is on the way.",
 *     "small_icon":       "ic_alert",
 *     "big_picture":      "https://cdn.example.com/banner.jpg",
 *     "large_icon":       "https://cdn.example.com/avatar.png",
 *     "bg_color":         "#FF2E6BE6",
 *     "led_color":        "FF0000FF",
 *     "visibility":       "public",
 *     "group":            "order_updates",
 *     "group_message":    "5 order updates",
 *     "android_ongoing":  "false",
 *     "channel_id":       "transactional",
 *     "sound":            "chime",
 *     "priority":         "high",
 *     "collapse_key":     "order_update",
 *     "actions":          "[{\"id\":\"track\",\"title\":\"Track\"},{\"id\":\"dismiss\",\"title\":\"Dismiss\"}]",
 *     "badge_count":      "3"
 *   }
 * }
 * ```
 *
 * | Key | Meaning |
 * |-----|---------|
 * | `title` | Notification title. Pre-translated by the backend. Falls back to `notification.title` from the FCM notification block if absent. |
 * | `body` | Notification body. Pre-translated by the backend. Falls back to `notification.body` if absent. |
 * | `small_icon` | Drawable resource name (e.g. `"ic_alert"`) for the status-bar icon. Priority: payload → `com.appsonair.apppush.default_notification_icon` meta-data → launcher icon (logs WARN). |
 * | `big_picture` | HTTPS URL of an image (JPEG/PNG) shown expanded via BigPictureStyle. Falls back to BigTextStyle if absent (and `image_url` is also absent) or if the download fails. Downscaled to fit 1024×1024. |
 * | `large_icon` | HTTPS URL **or** drawable resource name for the thumbnail on the collapsed notification. Independent of `big_picture` — set one, both, or neither. Hidden while expanded, so it never repeats the big picture. Downscaled to fit 256×256. |
 * | `image_url` | Legacy combined key: used for `big_picture` and/or `large_icon` when that key is absent, so older payloads render as before. Also where the FCM notification block's `image` lands. Prefer `big_picture`/`large_icon` for independent control. |
 * | `bg_color` | Hex ARGB string (e.g. `"#FF2E6BE6"` or `"2E6BE6"`) for the notification accent/background colour. On Android 8+ with `setColorized(true)` this tints the notification background. Overrides the `com.appsonair.apppush.default_notification_color` meta-data for this notification. |
 * | `led_color` | ARGB hex string for the device's LED notification light (e.g. `"FF0000FF"` = opaque blue). Pre-O only — LED is a channel-level attribute on Android 8+. |
 * | `visibility` | Lockscreen visibility: `"public"` (default — show full content), `"private"` (hide content), `"secret"` (hide entirely). |
 * | `group` | Group key for stacking multiple notifications under a single summary in the shade. All notifications with the same key are collapsed into one group. |
 * | `group_message` | Summary text shown on the group summary notification (e.g. `"5 order updates"`). Only used when `group` is set. |
 * | `android_ongoing` | `"true"` makes the notification sticky — it cannot be dismissed by swiping. Useful for active downloads or ongoing calls. Default: `"false"`. |
 * | `channel_id` | Android notification channel ID. Defaults to [CHANNEL_ID] if absent. |
 * | `sound` | Name of a file in the host app's `res/raw` (without extension). Falls back to the default sound. On Android 8+ a custom sound gets its own channel. |
 * | `priority` | Notification priority: `"max"`, `"high"` (default), `"default"`, `"low"`, `"min"`. On Android 8+ overridden by the channel's importance level. |
 * | `collapse_key` | Replaces an earlier notification with the same key instead of stacking. |
 * | `actions` | JSON array of `{"id","title"}` action buttons, max 3. Tapping fires `INotificationClickListener` with `result.actionId` set. |
 * | `badge_count` | Integer (as string) for the notification long-press dot count. App-icon badge is set separately by `PushFirebaseMessagingService`. |
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

    // AppsOnAir-specific notification defaults. Declare these in your AndroidManifest to
    // customise the small icon and accent colour for SDK-rendered (data-only) notifications.
    // For consistent look with Firebase-rendered (notification-block) messages, also declare:
    //   com.google.firebase.messaging.default_notification_icon  → same drawable
    //   com.google.firebase.messaging.default_notification_color → same color
    private const val META_ICON  = "com.appsonair.apppush.default_notification_icon"
    private const val META_COLOR = "com.appsonair.apppush.default_notification_color"

    // Android shows at most three action buttons; extra entries are ignored.
    private const val MAX_ACTIONS = 3

    // Decoded image bounds. A full-size photo (e.g. 4000×3000 ≈ 48 MB as ARGB) risks
    // OutOfMemoryError in the short-lived FCM service; the shade never shows more than about
    // a screen width for the big picture or ~48dp for the thumbnail anyway.
    private const val BIG_PICTURE_MAX_PX = 1024
    private const val LARGE_ICON_MAX_PX  = 256

    // Downloads larger than this are abandoned rather than decoded.
    private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024

    // Resolved once — buildAndNotify() runs per notification and these are PackageManager reads.
    @Volatile private var cachedSmallIcon = 0
    @Volatile private var cachedAccentColor = 0
    @Volatile private var iconFallbackLogged = false

    /**
     * Build and display a notification for [notification].
     *
     * **Threading**: Safe to call from any thread. Network downloads (image_url, large_icon URL)
     * are moved to a worker thread when called from the main thread. From a background thread
     * (e.g. [com.appsonair.apppush.service.PushFirebaseMessagingService.onMessageReceived])
     * they run inline so the notification is posted before the service is torn down.
     *
     * @param context      Application or service context.
     * @param notification Parsed push notification (title, body, data map).
     * @param launchIntent Optional intent fired when the user taps the notification.
     *                     Defaults to the app's default launcher activity.
     * @param channelId    Notification channel ID to use. Defaults to [CHANNEL_ID].
     */
    @JvmStatic
    @JvmOverloads
    fun show(
        context: Context,
        notification: PushNotification,
        launchIntent: Intent? = null,
        channelId: String = CHANNEL_ID
    ) {
        // big_picture / large_icon are independent images. image_url (and the FCM notification
        // block's image, which lands in notification.imageUrl) is the legacy combined key, used
        // for whichever of the two is absent — so payloads that only send image_url keep
        // rendering as before: the same image expanded and as the collapsed thumbnail.
        val legacyImageUrl = (notification.imageUrl ?: notification.data["image_url"])?.takeIf { it.isNotBlank() }
        val bigPictureUrl  = notification.data["big_picture"]?.takeIf { it.isNotBlank() } ?: legacyImageUrl
        val largeIconSrc   = notification.data["large_icon"]?.takeIf { it.isNotBlank() } ?: legacyImageUrl

        // large_icon can be a URL or a drawable name. Only URLs need network I/O.
        val needsNetwork = bigPictureUrl != null || largeIconSrc?.let(::isUrl) == true

        val loadAndNotify = {
            // Same URL in both slots (the common image_url case): fetch the bytes once, then
            // decode twice at each slot's size — not one full-size bitmap posted in both.
            val bigPictureBytes = bigPictureUrl?.let(::downloadBytes)
            val bigPictureBitmap = bigPictureBytes?.let { decodeScaled(it, BIG_PICTURE_MAX_PX, bigPictureUrl) }
            val largeIconBitmap = when {
                largeIconSrc == null -> null
                !isUrl(largeIconSrc) -> resolveLargeIconResource(context, largeIconSrc)
                else -> {
                    val bytes = if (largeIconSrc == bigPictureUrl) bigPictureBytes else downloadBytes(largeIconSrc)
                    bytes?.let { decodeScaled(it, LARGE_ICON_MAX_PX, largeIconSrc) }
                }
            }
            buildAndNotify(context, notification, launchIntent, channelId, bigPictureBitmap, largeIconBitmap)
        }

        if (needsNetwork && Looper.myLooper() == Looper.getMainLooper()) {
            // Hop to a worker — network on the main thread throws NetworkOnMainThreadException.
            Thread(loadAndNotify, "aoa-notif-image").start()
        } else {
            // Off the main thread (the FCM background path) load inline, so the notification is
            // posted before the service is torn down.
            loadAndNotify()
        }
    }

    // Builds and posts the notification.
    // [bigPictureBitmap] — downloaded image for BigPictureStyle, null when absent/failed.
    // [largeIconBitmap]  — resolved large circle icon, null when absent/failed.
    private fun buildAndNotify(
        context: Context,
        notification: PushNotification,
        launchIntent: Intent?,
        channelId: String,
        bigPictureBitmap: Bitmap?,
        largeIconBitmap: Bitmap?
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
        // AppPushService.setBadgeCount() — that one is a device-wide OEM call.
        val badgeCount = notification.data["badge_count"]?.toIntOrNull()?.takeIf { it >= 0 }

        // priority — optional data key. Accepted values (case-insensitive):
        //   "max"     → PRIORITY_MAX  (+2)  — time-critical alerts (e.g. incoming call)
        //   "high"    → PRIORITY_HIGH (+1)  — default; heads-up notification
        //   "default" → PRIORITY_DEFAULT (0)
        //   "low"     → PRIORITY_LOW  (-1)
        //   "min"     → PRIORITY_MIN  (-2)  — collapsed in shade, no interruption
        // On Android 8+ overridden by the channel's importance level.
        val priority = resolvePriority(notification.data["priority"])

        // visibility — lockscreen content visibility.
        //   "public"  → show full content on lockscreen (default)
        //   "private" → show notification but hide sensitive content
        //   "secret"  → hide notification entirely on secure lockscreen
        val visibility = resolveVisibility(notification.data["visibility"])

        // bg_color — per-notification accent/background colour (hex ARGB, e.g. "#FF2E6BE6").
        // Overrides the app-level com.appsonair.apppush.default_notification_color meta-data.
        // On Android 8+ setColorized(true) tints the notification background with this colour.
        val bgColor = notification.data["bg_color"]?.let { parseColor(it) }

        // led_color — ARGB hex string for the LED notification light (e.g. "FF0000FF" = blue).
        // Pre-Android 8 (O) only — LED is a channel-level attribute on O+ and cannot be set
        // per-notification. Ignored silently on O+.
        val ledColorInt = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            notification.data["led_color"]?.let { parseColor(it) } else null

        // group — groups multiple notifications in the shade under a collapsible header.
        // group_message — text shown on the summary row (e.g. "5 order updates").
        val group        = notification.data["group"]
        val groupMessage = notification.data["group_message"]

        // android_ongoing — "true" makes the notification sticky (cannot be swiped away).
        val ongoing = notification.data["android_ongoing"]?.lowercase() == "true"

        val intent = (launchIntent
            ?: context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    // addFlags, never `flags =`: getLaunchIntentForPackage() already sets
                    // FLAG_ACTIVITY_NEW_TASK, which is required to start an activity from a
                    // notification. Assigning over it made a tap silently do nothing while the
                    // app was backgrounded.
                    // CLEAR_TOP + SINGLE_TOP deliver this intent to the existing activity via
                    // onNewIntent() instead of starting a second copy.
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
                // After the data loop so the resolved URL wins over the data key.
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
            // Small icon priority: payload "small_icon" → meta-data → launcher icon fallback.
            .setSmallIcon(resolveSmallIcon(context, notification.data["small_icon"]))
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(priority)
            .setVisibility(visibility)
            .apply {
                pendingIntent?.let { setContentIntent(it) }

                // Colour: per-notification bg_color overrides the app-level accent meta-data.
                val colorToApply = bgColor ?: resolveAccentColor(context).takeIf { it != 0 }
                if (colorToApply != null) {
                    setColor(colorToApply)
                    // Android 8+ only: colorized tints the notification background, not just icon.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setColorized(true)
                    }
                }

                // Pre-O LED light support.
                if (ledColorInt != null) {
                    setLights(ledColorInt, 500, 2000)
                }

                // Pre-O the sound rides on the notification; O+ takes it from the channel above.
                if (soundUri != null && !useChannelSound) setSound(soundUri)

                badgeCount?.let { setNumber(it) }

                // Group — collapses multiple notifications under a shared header in the shade.
                if (group != null) {
                    setGroup(group)
                    // Only the summary row shows the group_message text.
                }
            }

        // actions — JSON array of {"id","title"} buttons, max 3.
        addActions(context, builder, notification, intent, notifId)

        // Style — BigPictureStyle when a big picture loaded, BigTextStyle otherwise.
        if (bigPictureBitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPictureBitmap)
                    // Hide the thumbnail while expanded — otherwise, with image_url in both
                    // slots, the same image shows twice: full size and as the thumbnail.
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(notification.title)
                    .setSummaryText(notification.body)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
        }
        // Collapsed-view thumbnail — independent of the big picture above.
        largeIconBitmap?.let { builder.setLargeIcon(it) }

        // Group summary — post a separate summary notification so Android can collapse the group.
        if (group != null) {
            val summary = NotificationCompat.Builder(context, effectiveChannelId)
                .setSmallIcon(resolveSmallIcon(context, notification.data["small_icon"]))
                .setContentTitle(notification.title)
                .setContentText(groupMessage ?: notification.body)
                .setGroup(group)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(priority)
                .apply {
                    val colorToApply = bgColor ?: resolveAccentColor(context).takeIf { it != 0 }
                    colorToApply?.let { setColor(it) }
                }
                .build()
            val summaryId = group.hashCode()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(group, summaryId, summary)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!AppPushService.isPermissionGranted(context)) {
            AppPushService.log(
                "[NotificationHelper] Notifications are disabled for this app — the system will " +
                "discard this notification (id=$notifId). Request POST_NOTIFICATIONS on Android 13+, " +
                "or check Settings if the user turned notifications off.",
                LogLevel.WARN
            )
        }

        // collapse_key — a newer notification with the same key replaces the previous one in the
        // tray instead of stacking. NotificationManager.notify(tag, id, ...) uses tag as the key.
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
        // A channel's sound and importance are immutable once created — that is exactly why a
        // custom sound gets its own channel ID rather than mutating the default one.
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
     * Resolves the small notification icon using a three-level priority chain:
     *
     * 1. **Payload** — `small_icon` data key (drawable resource name, e.g. `"ic_alert"`).
     *    Resolved per-notification (not cached) so different pushes can use different icons.
     * 2. **App-level meta-data** — `com.appsonair.apppush.default_notification_icon` in
     *    `AndroidManifest.xml`. Resolved once and cached for the process lifetime.
     * 3. **Launcher icon fallback** — Android renders it as a white square (small icons must be
     *    alpha-only silhouettes). Logged once as WARN so the misconfiguration is diagnosable.
     */
    private fun resolveSmallIcon(context: Context, payloadIconName: String?): Int {
        // 1. Payload icon — resolve by name. Per-notification, bypass the cache.
        if (!payloadIconName.isNullOrBlank()) {
            val resId = context.resources.getIdentifier(payloadIconName, "drawable", context.packageName)
            if (resId != 0) {
                AppPushService.log("[NotificationHelper] Using payload small_icon=\"$payloadIconName\" (resId=$resId)")
                return resId
            }
            AppPushService.log(
                "[NotificationHelper] small_icon=\"$payloadIconName\" not found in res/drawable — " +
                "falling back to app-level default.",
                LogLevel.WARN
            )
        }

        // 2 & 3. Meta-data → launcher icon. Both are app-wide constants; cache them.
        cachedSmallIcon.takeIf { it != 0 }?.let { return it }
        val fromMeta = appMetaData(context)?.getInt(META_ICON, 0) ?: 0
        val resolved = if (fromMeta != 0) fromMeta else {
            if (!iconFallbackLogged) {
                iconFallbackLogged = true
                AppPushService.log(
                    "[NotificationHelper] No \"$META_ICON\" meta-data — falling back to the " +
                    "launcher icon, which Android renders as a white square. Add " +
                    "ic_stat_appsonair_default to res/drawable and declare it via that meta-data " +
                    "in your AndroidManifest.",
                    LogLevel.WARN
                )
            }
            context.applicationInfo.icon
        }
        cachedSmallIcon = resolved
        return resolved
    }

    /** `large_icon` given as a drawable resource name in the host app, decoded to a [Bitmap]. */
    private fun resolveLargeIconResource(context: Context, name: String): Bitmap? {
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (resId == 0) {
            AppPushService.log(
                "[NotificationHelper] large_icon=\"$name\" is not a URL and not found in " +
                "res/drawable — no large icon will be shown.",
                LogLevel.WARN
            )
            return null
        }
        return runCatching { BitmapFactory.decodeResource(context.resources, resId) }
            .getOrElse {
                AppPushService.log(
                    "[NotificationHelper] Failed to decode large_icon drawable \"$name\"",
                    LogLevel.WARN, it
                )
                null
            }
    }

    private fun isUrl(src: String): Boolean = src.startsWith("http", ignoreCase = true)

    /** Accent colour from the `com.appsonair.apppush.default_notification_color` meta-data; 0 when unset. */
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
     * Maps the `priority` FCM data key to a [NotificationCompat] priority constant.
     * Falls back to [NotificationCompat.PRIORITY_HIGH] when absent or unrecognised.
     * On Android 8+ overridden by the channel's importance level.
     */
    private fun resolvePriority(value: String?): Int = when (value?.lowercase()) {
        "max"     -> NotificationCompat.PRIORITY_MAX
        "high"    -> NotificationCompat.PRIORITY_HIGH
        "default" -> NotificationCompat.PRIORITY_DEFAULT
        "low"     -> NotificationCompat.PRIORITY_LOW
        "min"     -> NotificationCompat.PRIORITY_MIN
        else      -> NotificationCompat.PRIORITY_HIGH
    }

    /**
     * Maps the `visibility` FCM data key to a [NotificationCompat] visibility constant.
     * Defaults to [NotificationCompat.VISIBILITY_PUBLIC] when absent or unrecognised.
     */
    private fun resolveVisibility(value: String?): Int = when (value?.lowercase()) {
        "private" -> NotificationCompat.VISIBILITY_PRIVATE
        "secret"  -> NotificationCompat.VISIBILITY_SECRET
        else      -> NotificationCompat.VISIBILITY_PUBLIC
    }

    /**
     * Parses a hex colour string (with or without `#`, RGB or ARGB) to an [Int] colour.
     * Returns null if the string cannot be parsed.
     *
     * Accepted formats: `"2E6BE6"`, `"#2E6BE6"`, `"FF2E6BE6"`, `"#FF2E6BE6"`.
     */
    private fun parseColor(hex: String): Int? {
        val h = hex.trim().trimStart('#')
        // Pad 6-digit RGB to 8-digit ARGB by prepending full-opacity alpha.
        val normalized = when (h.length) {
            6    -> "FF$h"
            8    -> h
            else -> return null
        }
        return runCatching { Color.parseColor("#$normalized") }.getOrElse {
            AppPushService.log(
                "[NotificationHelper] Could not parse colour \"$hex\" — ignored.",
                LogLevel.WARN
            )
            null
        }
    }

    /**
     * Resolve the "sound" data key to a res/raw URI.
     * Returns null when the key is absent, logs a warning when the resource does not exist.
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
            // Icon 0 — text-only action, standard presentation on API 24+.
            builder.addAction(0, title, pendingIntent)
            added++
        }
    }

    // Downloads an image's raw bytes on the calling thread (blocking — never the main thread).
    // Returns null on any failure — every path is logged, so "no image key sent" and "image
    // fetch failed" are distinguishable in Logcat.
    private fun downloadBytes(urlString: String): ByteArray? {
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
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                AppPushService.log(
                    "[NotificationHelper] Image too large (${connection.contentLengthLong} bytes, " +
                        "max $MAX_IMAGE_BYTES) — skipped: $urlString",
                    LogLevel.WARN
                )
                return null
            }
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    // Content-Length can be absent or wrong — enforce the cap while reading.
                    if (out.size() > MAX_IMAGE_BYTES) {
                        AppPushService.log(
                            "[NotificationHelper] Image exceeded $MAX_IMAGE_BYTES bytes — skipped: $urlString",
                            LogLevel.WARN
                        )
                        return null
                    }
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            // Catches NetworkOnMainThreadException, timeouts, DNS/TLS failures, cleartext
            // blocks and non-HTTP URLs (ClassCastException).
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

    /**
     * Decodes [bytes] so neither side exceeds [maxPx], keeping the aspect ratio. Subsamples
     * during decode (inSampleSize, power of two) so a huge image is never held at full size,
     * then scales the remainder down exactly. Null when the bytes aren't a decodable image.
     */
    private fun decodeScaled(bytes: ByteArray, maxPx: Int, source: String): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            AppPushService.log(
                "[NotificationHelper] Image decoded to null (not a valid image?): $source",
                LogLevel.WARN
            )
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
                sample *= 2
            }
            val sampled = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            )
            sampled?.let { bitmap ->
                val scale = minOf(1f, maxPx.toFloat() / maxOf(bitmap.width, bitmap.height))
                val result = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    ).also { if (it !== bitmap) bitmap.recycle() }
                } else {
                    bitmap
                }
                AppPushService.log(
                    "[NotificationHelper] Image loaded (${bounds.outWidth}x${bounds.outHeight} → " +
                        "${result.width}x${result.height}): $source"
                )
                result
            }
        }
    } catch (t: Throwable) {
        // OutOfMemoryError included — a failed image must never fail the notification.
        AppPushService.log("[NotificationHelper] Image decode failed for $source", LogLevel.WARN, t)
        null
    }
}
