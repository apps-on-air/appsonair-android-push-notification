package com.appsonair.apppush

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.appsonair.core.services.CoreService
import com.appsonair.apppush.services.PushSubscriptionService
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

object AppPushService {

    private const val PERMISSION_REQUEST_CODE = 4107

    @Volatile private var isInitialized = false

    internal lateinit var appContext: Context

    internal var appId: String = ""
        private set
    internal val storage by lazy { PushStorage(appContext) }

    internal var listener: PushListener? = null

    internal val tags = mutableMapOf<String, String>()
   
    private var languageOverride: String? = null

    internal var language: String
        get() = languageOverride
            ?: if (isInitialized) CoreService.getLanguage(appContext)
               else Locale.getDefault().language
        set(value) {
            languageOverride = value
        }
    internal var externalId: String? = null
    internal var isOptedOut: Boolean = false
    internal val aliases = mutableMapOf<String, String>()
    internal val emails = mutableListOf<String>()
    // AOA:Future — SMS support not included in push SDK scope
    // internal val smsNumbers = mutableListOf<String>()
    internal val foregroundListeners = mutableListOf<INotificationLifecycleListener>()
    internal val clickListeners = mutableListOf<INotificationClickListener>()
    internal val permissionObservers = mutableListOf<INotificationPermissionObserver>()
    internal val pushSubscriptionObservers = mutableListOf<IPushSubscriptionObserver>()
    internal val userStateObservers = mutableListOf<IUserStateObserver>()
    internal var previousPermission: Boolean? = null

    @JvmStatic
    var subscriptionId: String? = null
        internal set

    @JvmStatic
    var isTestDevice: Boolean
        get() = if (isInitialized) storage.getBoolean("is_test_device", false) else false
        set(value) {
            if (isInitialized) {
                storage.putBoolean("is_test_device", value)
                log("isTestDevice set to $value. Included in the next registration.")
            }
        }

    private var _consentRequired: Boolean = false
    private var _consentGiven: Boolean = false

    val User get() = PushUser
    val Notifications get() = PushNotifications
    val Debug get() = PushDebug

    /**
     * Call once in Application.onCreate() before anything else.
     *
     * The app ID is read from the host app's AndroidManifest by AppsOnAir Core, the same way
     * AppLink, AppSync, and AppRemark read it. Declare it under the <application> tag:
     *
     *     <meta-data android:name="AppsonairAppId" android:value="YOUR_APP_ID" />
     *
     * @param context Application context.
     * @param debug   Print SDK logs to Logcat (deprecated — prefer Debug.logLevel). Keep false in production.
     */
    @JvmStatic
    fun initialize(context: Context, debug: Boolean = false) {
        
        appContext = context.applicationContext
        if (debug) PushDebug.logLevel = LogLevel.DEBUG
        isInitialized = true

        // and logs its own guidance when the entry is missing. The SDK keeps initializing so
        // notification display still works, but backend registration cannot succeed.
        appId = CoreService.getAppId(appContext)
        if (appId.isBlank()) {
            log(
                "AppsonairAppId meta-data missing from AndroidManifest. Add " +
                    "<meta-data android:name=\"AppsonairAppId\" android:value=\"YOUR_APP_ID\" /> " +
                    "under <application>. Subscription registration cannot succeed without it.",
                LogLevel.ERROR
            )
        }

        val firebaseReady = runCatching {
            FirebaseApp.getApps(appContext).isNotEmpty() ||
                FirebaseApp.initializeApp(appContext) != null
        }.getOrDefault(false)
        if (firebaseReady) {
            log("Firebase initialized by SDK.")
        } else {
            log(FIREBASE_SETUP_MESSAGE, LogLevel.ERROR)
        }

        if (_consentRequired) storage.putBoolean("consent_required", true)
        if (_consentGiven)    storage.putBoolean("consent_given", true)
       
        _consentRequired = storage.getBoolean("consent_required", _consentRequired)
        _consentGiven    = storage.getBoolean("consent_given",    _consentGiven)

        externalId = storage.getString("external_id")
        isOptedOut = storage.getBoolean("is_opted_out", false)
        languageOverride = storage.getString("language")
        storage.getString("tags_json")?.let { json ->
            runCatching { JSONObject(json) }.getOrNull()?.let { obj ->
                obj.keys().forEach { key -> tags[key] = obj.getString(key) }
            }
        }
        storage.getString("aliases_json")?.let { json ->
            runCatching { JSONObject(json) }.getOrNull()?.let { obj ->
                obj.keys().forEach { key -> aliases[key] = obj.getString(key) }
            }
        }

        subscriptionId = storage.getString("subscription_id")

        log("SDK ready. deviceId=${getDeviceId()} subscriptionId=${subscriptionId ?: "(none yet)"}")

        (appContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
            ?: log("Application context unavailable — call handleNotificationTapIntent() manually.", LogLevel.WARN)

        // Drives the FCM foreground render check, the permission re-read, and the event flush.
        PushSessionManager.start()

        if (firebaseReady) {
            refreshFcmToken()
        } else {
            emitError(
                PushError(
                    code = PushError.Code.FIREBASE_NOT_CONFIGURED,
                    message = FIREBASE_SETUP_MESSAGE
                )
            )
        }
    }

    private const val FIREBASE_SETUP_MESSAGE =
        "Firebase is not configured, so push notifications cannot work. Add google-services.json " +
            "to your app/ folder and apply the com.google.gms.google-services plugin, then " +
            "rebuild. See the Firebase setup section of the AppPushService README."

    
    private const val EXTRA_TAP_HANDLED = "com.appsonair.apppush.tapHandled"

    private var currentActivityRef: WeakReference<Activity>? = null

    internal val currentActivity: Activity? get() = currentActivityRef?.get()

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            handleNotificationTapIntent(activity.intent)
        }

        override fun onActivityResumed(activity: Activity) {
            currentActivityRef = WeakReference(activity)
            handleNotificationTapIntent(activity.intent)

            checkPermissionChange()
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            if (currentActivityRef?.get() === activity) currentActivityRef = null
        }
    }

    @JvmStatic
    var onSilentPushReceived: ((data: Map<String, String>) -> Unit)? = null

    /** Register a listener to receive push events and errors. */
    @JvmStatic
    fun setListener(pushListener: PushListener?) {
        listener = pushListener
    }

    @JvmStatic
    fun getDeviceId(): String {
        if (!checkInitialized()) return ""
        return storage.deviceId
    }

    internal val hasRequestedPermission: Boolean
        get() = isInitialized && storage.getBoolean("has_requested_permission", false)

    @JvmStatic
    fun requestNotificationPermission(activity: Activity) {
        if (!checkInitialized()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ActivityCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED) return

        storage.putBoolean("has_requested_permission", true)
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Fetch the current FCM registration token.
     * Called automatically on initialize() and on token refresh.
     * Fires listener.onTokenUpdated() on success.
     */
    @JvmStatic
    fun refreshFcmToken() {
        if (!checkInitialized()) return
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrElse { error ->
            log(FIREBASE_SETUP_MESSAGE, LogLevel.ERROR, error)
            emitError(
                PushError(
                    code = PushError.Code.FIREBASE_NOT_CONFIGURED,
                    message = FIREBASE_SETUP_MESSAGE,
                    cause = error
                )
            )
            return
        }
        
        messaging.token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
        
                val reason = task.exception?.message ?: "Unknown error"
                log("FCM token fetch failed: $reason", LogLevel.ERROR, task.exception)
                emitError(
                    PushError(
                        code = PushError.Code.TOKEN_FETCH_FAILED,
                        message = "Failed to get FCM token: $reason",
                        cause = task.exception
                    )
                )
                return@addOnCompleteListener
            }
            handleFcmToken(task.result)
        }
    }

    internal fun handleFcmToken(token: String) {
        saveAndAnnounceToken(token)
        PushSubscriptionService.register(appContext, "token")
        listener?.onTokenUpdated(token)
    }

    
    internal fun handleRotatedToken(token: String) {
        saveAndAnnounceToken(token)
        PushSubscriptionService.updateToken(token)
        listener?.onTokenUpdated(token)
    }

    private fun saveAndAnnounceToken(token: String) {
        storage.saveFcmToken(token)
        log("FCM token received.")

        // VERBOSE-gated: an FCM token is a send credential for this device, so it must never
        // reach a production Logcat. Chunked because Logcat truncates long single lines.
        if (PushDebug.logLevel == LogLevel.VERBOSE) {
            log("====== FCM TOKEN START ======", LogLevel.VERBOSE)
            token.chunked(200).forEachIndexed { i, chunk ->
                log("[$i] $chunk", LogLevel.VERBOSE)
            }
            log("====== FCM TOKEN END ========", LogLevel.VERBOSE)
        }
    }

    @JvmStatic
    fun getInstallationId() {
        if (!checkInitialized()) return
        val installations = runCatching { FirebaseInstallations.getInstance() }.getOrElse { error ->
            log(FIREBASE_SETUP_MESSAGE, LogLevel.ERROR, error)
            emitError(
                PushError(
                    code = PushError.Code.FIREBASE_NOT_CONFIGURED,
                    message = FIREBASE_SETUP_MESSAGE,
                    cause = error
                )
            )
            return
        }
        installations.id.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                emitError(
                    PushError(
                        code = PushError.Code.INSTALLATION_ID_FETCH_FAILED,
                        message = "Failed to get Firebase Installation ID. Check Firebase setup.",
                        cause = task.exception
                    )
                )
                return@addOnCompleteListener
            }
            val id = task.result
            storage.saveInstallationId(id)
            log("Firebase Installation ID received.")
            listener?.onInstallationIdUpdated(id)
        }
    }

    @JvmStatic
    fun setSubscriptionId(id: String) {
        if (id.isBlank()) {
            log("setSubscriptionId() failed - id cannot be blank.", LogLevel.ERROR)
            return
        }
        if (!checkInitialized()) return
        subscriptionId = id
        storage.putString("subscription_id", id)
        log("Subscription ID set: $id. Included in all future event reports.")
    }

    @JvmStatic
    fun login(externalId: String) {
        if (externalId.isBlank()) {
            log("login() failed - externalId cannot be blank.", LogLevel.ERROR)
            return
        }
        if (!checkInitialized()) return
        AppPushService.externalId = externalId
        storage.putString("external_id", externalId)
        PushSubscriptionService.updateExternalId(externalId)
        log("User logged in. externalId=$externalId")
        val state = UserChangedState(UserState(externalId = externalId, appsOnAirId = getDeviceId()))
        userStateObservers.forEach { it.onUserStateDidChange(state) }
    }

    @JvmStatic
    fun logout() {
        if (!checkInitialized()) return
        externalId = null
        tags.clear()
        aliases.clear()
        storage.remove("external_id")
        storage.remove("tags_json")
        storage.remove("aliases_json")
        // Detach server-side, so pushes targeted at that user stop arriving here.
        PushSubscriptionService.clearExternalId()
        log("User logged out. Reverted to anonymous.")
        val state = UserChangedState(UserState(externalId = null, appsOnAirId = getDeviceId()))
        userStateObservers.forEach { it.onUserStateDidChange(state) }
    }

    @JvmStatic
    var consentRequired: Boolean
        get() = if (isInitialized) storage.getBoolean("consent_required", _consentRequired) else _consentRequired
        set(value) {
            _consentRequired = value
            if (isInitialized) storage.putBoolean("consent_required", value)
        }

    @JvmStatic
    var consentGiven: Boolean
        get() = if (isInitialized) storage.getBoolean("consent_given", _consentGiven) else _consentGiven
        set(value) {
            _consentGiven = value
            if (isInitialized) {
                storage.putBoolean("consent_given", value)
                log("Consent ${if (value) "given" else "revoked"}.")
            }
        }

    /**
     * Returns `true` if the user has granted notification permission.
     *
     * Uses [NotificationManagerCompat.areNotificationsEnabled] which handles all
     * API levels correctly:
     * - API 33+ — checks the `POST_NOTIFICATIONS` runtime permission.
     * - API < 33  — notifications are enabled by default; always returns `true`
     *               unless the user disabled them in device Settings.
     *
     * Call this to gate notification-dependent features or decide whether to
     * show an in-app rationale before calling [requestNotificationPermission].
     */
    @JvmStatic
    fun isPermissionGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    
    @JvmStatic
    fun clearAllNotifications(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()
        log("All notifications cleared.")
    }

    /**
     * Set the badge count shown on the app icon on supported launchers.
     *
     * Android has no standard OS-level badge API. This method attempts the
     * well-known launcher-specific broadcast/content-resolver patterns used
     * by Samsung, Huawei, Xiaomi (MIUI), Sony, and ASUS launchers.
     * On unsupported launchers the call is a no-op (no crash).
     *
     * Pass `0` to clear the badge.
     *
     * For guaranteed badge support across all launchers consider adding the
     * [ShortcutBadger](https://github.com/leolin310148/ShortcutBadger) library,
     * which is not included here to keep the SDK dependency-free.
     *
     * Persists [count] as the baseline read by [resolveBadgeCount] for `badge_type: "increase"`
     * payloads — there is no OS API on Android (or iOS) to read back the badge value currently
     * shown, so the SDK's own last-set value is the only available baseline.
     *
     * @param context Application context.
     * @param count   Badge count to display. Pass `0` to clear.
     */
    @JvmStatic
    fun setBadgeCount(context: Context, count: Int) {
        
        if (isInitialized) storage.prefs.edit().putInt("badge_count", count).commit()
        val launcherClass = getLauncherClassName(context)

        // Samsung — uses a broadcast intent
        runCatching {
            val intent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count",              count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name",   launcherClass)
            }
            context.sendBroadcast(intent)
        }

        // Huawei (EMUI) — uses a ContentResolver call
        runCatching {
            val bundle = Bundle().apply {
                putString("package",     context.packageName)
                putString("class",       launcherClass)
                putInt("badgenumber",    count)
            }
            context.contentResolver.call(
                Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                "change_badge", null, bundle
            )
        }

        // Xiaomi (MIUI) — uses a broadcast intent with message text.
        // AOA: On many MIUI versions this broadcast is NOT a "set" — the launcher adds the
        // value to whatever badge is already showing (observed: sending "4" then "5" produced
        // "9", not "5"). Clear to empty string first (which MIUI does treat as a reset to 0)
        // and only then send the target count, so the result is always an absolute value —
        // 0 + count = count — regardless of whether a given MIUI build increments or replaces.
        runCatching {
            fun miuiIntent(text: String) = Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE").apply {
                putExtra(
                    "android.intent.extra.update_application_component_name",
                    "${context.packageName}/$launcherClass"
                )
                putExtra("android.intent.extra.update_application_message_text", text)
            }
            if (count > 0) context.sendBroadcast(miuiIntent(""))
            context.sendBroadcast(miuiIntent(if (count > 0) count.toString() else ""))
        }

        // Sony — uses a ContentProvider insert
        runCatching {
            val cv = android.content.ContentValues().apply {
                put("badge_count",    count)
                put("package_name",  context.packageName)
                put("activity_name", launcherClass)
            }
            context.contentResolver.insert(
                Uri.parse("content://com.sonyericsson.home.badgecontentprovider/badges/"),
                cv
            )
        }

        // ASUS ZenUI — uses a broadcast intent
        runCatching {
            val intent = Intent("com.asus.badge.ACTION_BADGE_COUNT_UPDATE").apply {
                putExtra("packageName", context.packageName)
                putExtra("count",       count)
            }
            context.sendBroadcast(intent)
        }

        log("Badge count set to $count. Persisted as increase baseline: ${storage.getInt("badge_count", -1)}.")
    }

    /**
     * The badge count the SDK last applied via [setBadgeCount] (or a `badge_count` payload).
     *
     * Android has no OS API to read the badge a launcher is currently showing, so this returns
     * the SDK's own persisted value — the same baseline [resolveBadgeCount] uses. It can differ
     * from what is on screen if the launcher ignored the broadcast (see [setBadgeCount]).
     *
     * Returns 0 before [initialize].
     */
    @JvmStatic
    fun getBadgeCount(): Int = if (isInitialized) storage.getInt("badge_count", 0) else 0

    /**
     * Clear the app icon badge. Shortcut for [setBadgeCount] with `count = 0`.
     * Also resets the persisted baseline used by [resolveBadgeCount], so a subsequent
     * `badge_type: "increase"` payload starts counting from zero again.
     */
    @JvmStatic
    fun clearBadgeCount(context: Context) {
        setBadgeCount(context, 0)
    }

    /**
     * Resolve a push payload's `badge_count` against the persisted baseline for
     * `badge_type: "increase"` semantics.
     *
     * Neither Android nor iOS' raw push transport (FCM data payload, or APNs `aps.badge`) has a
     * true "increment" instruction — both are always an absolute final value. "Increase" is a
     * value the SDK computes client-side, adding [rawCount] to the last value passed to
     * [setBadgeCount] (0 if none yet, since there is no OS API to read the badge currently shown).
     *
     * @param rawCount  The payload's `badge_count`, parsed to an `Int`.
     * @param badgeType The payload's `badge_type` — `"increase"` (case-insensitive) adds to the
     *                  persisted baseline; anything else (including absent) is treated as `"set"`
     *                  and returns [rawCount] unchanged.
     * @return The resolved absolute badge count, never negative. Pass this to [setBadgeCount].
     */
    @JvmStatic
    fun resolveBadgeCount(rawCount: Int, badgeType: String?): Int {
        val isIncrease = badgeType.equals("increase", ignoreCase = true)
        val baseline = if (isIncrease) {
            if (isInitialized) storage.getInt("badge_count", 0) else 0
        } else {
            0
        }
        val resolved = (baseline + rawCount).coerceAtLeast(0)
        
        log(
            "resolveBadgeCount: badgeType=${if (isIncrease) "increase" else "set"} " +
                "baseline=$baseline rawCount=$rawCount -> resolved=$resolved",
            LogLevel.INFO
        )
        return resolved
    }

    /**
     * Parse a push payload's badge instruction into `(rawCount, badgeType)`, ready for
     * [resolveBadgeCount]. Supports two payload shapes, checked in this order:
     *
     * 1. **Nested `badge` object (recommended)** — FCM's `data` payload is `Map<String, String>`,
     *    so the object must be sent JSON-*encoded as a string* value:
     *    ```json
     *    "data": { "badge": "{\"type\":\"increment\",\"value\":1}" }
     *    ```
     *    `type` is `"increment"` (same as `badge_type: "increase"`) or `"set"`/absent (absolute).
     *    `value` is the count or delta, matching `badge_count`.
     *
     * 2. **Legacy flat keys** — `"badge_count": "1"`, `"badge_type": "increase"`. Read only when
     *    `badge` is absent, so existing payloads built before the nested shape keep working.
     *
     * @return `(rawCount, badgeType)`, or `null` if the payload carries no badge instruction —
     *   the caller should then skip badge handling entirely rather than call [resolveBadgeCount].
     */
    @JvmStatic
    fun parseBadgePayload(data: Map<String, String>): Pair<Int, String>? {
        data["badge"]?.let { raw ->
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            val value = obj?.let { if (it.has("value")) it.optInt("value", Int.MIN_VALUE) else Int.MIN_VALUE }
            if (value != null && value != Int.MIN_VALUE) {
                val type = obj.optString("type", "set")
                val badgeType = if (type.equals("increment", ignoreCase = true) ||
                    type.equals("increase", ignoreCase = true)
                ) "increase" else "set"
                return value to badgeType
            }
            // Present but unparsable — fall through to the legacy keys rather than silently
            // dropping the badge update. WARN, not ERROR: this is a malformed payload from the
            // backend rather than a host misconfiguration, it recurs on every affected push,
            // and the fallback keeps the badge working.
            log(
                "\"badge\" data key is not valid JSON or is missing \"value\" — " +
                    "falling back to badge_count/badge_type if present. raw=\"$raw\"",
                LogLevel.WARN
            )
        }
        val legacyCount = data["badge_count"]?.toIntOrNull() ?: return null
        return legacyCount to (data["badge_type"] ?: "set")
    }


    private fun getLauncherClassName(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.name ?: ""
    }

    @JvmStatic
    fun handleNotificationTapIntent(intent: Intent?) {
        intent ?: return
        
        if (intent.getBooleanExtra(EXTRA_TAP_HANDLED, false)) return
        val notifId    = intent.getStringExtra("notification_id")
        val title      = intent.getStringExtra("title")
        val body       = intent.getStringExtra("body")
        
        val actionId   = intent.getStringExtra("action_id")
        
        if (notifId == null && title == null && body == null) return

        intent.putExtra(EXTRA_TAP_HANDLED, true)

        val dataBundle = intent.extras
        val dataMap = mutableMapOf<String, String>()
        dataBundle?.keySet()?.forEach { key ->
            dataBundle.getString(key)?.let { dataMap[key] = it }
        }
        val notification = PushNotification(
            id    = notifId,
            title = title,
            body  = body,
            data  = dataMap,
            imageUrl = intent.getStringExtra("image_url")
        )
        val event = NotificationClickEvent(
            notification = notification,
            result = NotificationClickResult(actionId = actionId, url = dataMap["url"])
        )
        clickListeners.forEach { it.onClick(event) }
        listener?.onNotificationOpened(notification)

        // Enqueue click/open event — sent to backend on next flush.
        // TODO: API — POST /events/opened or /events/clicked (see PushEventQueue)
        val pushEventType = if (actionId != null) PushEventType.CLICKED else PushEventType.OPENED
        PushEventQueue.enqueue(PushEvent(
            type           = pushEventType,
            notificationId = notifId,
            subscriptionId = subscriptionId,
            actionId       = actionId,
            deviceId       = storage.deviceId
        ))
        log(
            "Cold-start notification tap handled. id=$notifId " +
            "event=${pushEventType.name} subscriptionId=${subscriptionId ?: "nil"} " +
            "[TODO] POST /events/${if (actionId != null) "clicked" else "opened"}"
        )
    }

    internal fun checkPermissionChange() {
        if (!isInitialized) return
        val current = isPermissionGranted(appContext)
        val prev = previousPermission
        if (prev != null && prev != current) {
            permissionObservers.forEach { it.onNotificationPermissionDidChange(current) }
            PushSubscriptionService.updateEnabled(current)
        }
        previousPermission = current
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    internal fun dispatchNotification(notification: PushNotification) {
        log("Notification received: ${notification.id}")
        mainHandler.post { listener?.onNotificationReceived(notification) }

        // Enqueue a local RECEIVED event for tracking purposes.
        // No backend call for free tier — TODO: POST /events/received if BE requests it.
        PushEventQueue.enqueue(PushEvent(
            type           = PushEventType.RECEIVED,
            notificationId = notification.id,
            subscriptionId = subscriptionId,
            deviceId       = storage.deviceId
        ))
    }

    internal fun dispatchSilentPush(data: Map<String, String>) {
        log("Silent push received. Rendering skipped.")
        onSilentPushReceived?.invoke(data)
    }

    internal fun dispatchNotificationOpened(notification: PushNotification) {
        log("Notification opened: ${notification.id}")
        mainHandler.post { listener?.onNotificationOpened(notification) }
    }

    internal fun emitError(error: PushError) {
        log("Error [${error.code}] ${error.message}")
        mainHandler.post { listener?.onError(error) }
    }

    /**
     * The SDK's single logging path — nothing calls android.util.Log directly.
     *
     * ERROR and FATAL are always emitted, even at the default [LogLevel.NONE]. They fire only
     * when the SDK cannot function (no app ID, no Firebase config, token fetch failed), and
     * initialize() usually runs before the host app can call setListener(), so the matching
     * PushError has nowhere to go. Everything quieter is diagnostic output and stays gated by
     * [PushDebug.logLevel].
     *
     * Dispatches to the matching Android priority instead of always using Log.d, so a warning
     * reads as W in Logcat, stays filterable by priority, and reaches crash reporters that
     * collect only W/E. Android has no FATAL priority, so it maps to E.
     *
     * @param throwable appended as a stack trace when supplied.
     */
    internal fun log(message: String, level: LogLevel = LogLevel.DEBUG, throwable: Throwable? = null) {
        if (level == LogLevel.NONE) return
        val isFailure = level == LogLevel.ERROR || level == LogLevel.FATAL
        if (!isFailure && PushDebug.logLevel.ordinal < level.ordinal) return

        val tag = "AppPushService"
        val text = if (throwable == null) message
        else "$message\n${android.util.Log.getStackTraceString(throwable)}"

        when (level) {
            LogLevel.FATAL, LogLevel.ERROR -> android.util.Log.e(tag, text)
            LogLevel.WARN                  -> android.util.Log.w(tag, text)
            LogLevel.INFO                  -> android.util.Log.i(tag, text)
            LogLevel.DEBUG                 -> android.util.Log.d(tag, text)
            LogLevel.VERBOSE               -> android.util.Log.v(tag, text)
            LogLevel.NONE                  -> Unit
        }
    }

    private fun checkInitialized(): Boolean {
        if (isInitialized) return true
        log("AppPushService.initialize() must be called first.", LogLevel.ERROR)
        emitError(
            PushError(
                code = PushError.Code.NOT_INITIALIZED,
                message = "Call AppPushService.initialize() before using the SDK."
            )
        )
        return false
    }
}

internal class PushStorage(private val context: Context) {

    internal val prefs: SharedPreferences = context.getSharedPreferences(
        "appsonair_push", Context.MODE_PRIVATE
    )

    val deviceId: String get() = CoreService.getDeviceId(context)

    fun saveFcmToken(token: String) = prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    fun getFcmToken(): String? = prefs.getString(KEY_FCM_TOKEN, null)

    fun saveInstallationId(id: String) = prefs.edit().putString(KEY_INSTALLATION_ID, id).apply()
    fun getInstallationId(): String? = prefs.getString(KEY_INSTALLATION_ID, null)

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun remove(key: String) = prefs.edit().remove(key).apply()
    fun getBoolean(key: String, default: Boolean = false): Boolean = prefs.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getLong(key: String): Long = prefs.getLong(key, 0L)
    fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)
    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    companion object {
        private const val KEY_FCM_TOKEN       = "fcm_token"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
