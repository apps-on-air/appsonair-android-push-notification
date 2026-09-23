# AppPushService — Android SDK

An Android push notification SDK to receive push
events, and display rich media notifications with minimal setup.

```kotlin
AppPushService.initialize(this)                    // Application.onCreate()
AppPushService.requestNotificationPermission(this) // Activity
```

> [!WARNING]
> **Beta release — not for production use.**
>
> `1.0.1-beta` is an early preview, intended for evaluation, prototypes, and internal
> test builds. Do **not** ship it in a production app or one with a large user base.
>
> - The public API may change without notice and may not stay source-compatible —
>   expect to update your integration between releases.
> - Breaking changes are not limited to major versions while the SDK is pre-1.0.
> - Not yet proven at scale; some behaviour is still unverified in real-world use.
>
> Pin this exact version rather than a version range, and re-test on every upgrade.

---

## Contents

**Getting started** — [Requirements](#requirements) · [Firebase setup](#firebase-setup) · [Install](#install) · [Quick start](#quick-start)

**Guides** — [Logging](#logging) · [Identity](#identity) · [Consent](#consent) · [Tags](#tags) · [Language](#language) · [Aliases & email](#aliases--email) · [Opt-in / opt-out](#opt-in--opt-out) · [Permission](#permission) · [Foreground display](#foreground-display) · [Taps](#handling-taps) · [Channels](#channels) · [Dismissing](#dismissing-notifications) · [Badge](#badge-count) · [Rich media](#rich-media) · [Test device](#test-device)

**Reference** — [Payload keys](#payload-reference) · [API](#api-reference) · [Types](#types) · [Troubleshooting](#troubleshooting) · [Security](#security)

---

## Requirements

| | Minimum |
|---|---|
| Android | API 24 (7.0) |
| Kotlin | 1.8+ |
| JVM target | 17 |
| Firebase | `google-services.json` |

---

## Firebase setup

** Create the app.** In the [Firebase Console](https://console.firebase.google.com), open your
project → **Add app → Android** → enter your package name → download `google-services.json` into
your `app/` folder:

```
your-app/
└── app/
    └── google-services.json   ← here
```
---

## Install

> **Beta.** Pin this exact version — `1.0.1-beta` is a preview and the API may change
> between releases. See [the notice above](#apppushservice--android-sdk) before adopting it.

App `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services")
}

dependencies {
    implementation("com.github.apps-on-air:appsonair-android-push-notification:1.0.1-beta")
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
```

Root `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.gms.google-services") version "4.4.2" apply false
}
```

`settings.gradle.kts` — both this SDK and AppsOnAir Core are distributed through JitPack, so the
repository is required:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

---

## Quick start

### 1. Initialize

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AppPushService.Debug.logLevel = LogLevel.VERBOSE  // optional, before initialize()
        AppPushService.initialize(this)
    }
}
```

### 2. Declare your app ID and icon

```xml
<application android:name=".MyApp" ...>

    <meta-data
        android:name="AppsonairAppId"
        android:value="YOUR_APP_ID" />

    <meta-data
        android:name="com.google.firebase.messaging.default_notification_icon"
        android:resource="@drawable/ic_notification" />
    <meta-data
        android:name="com.google.firebase.messaging.default_notification_color"
        android:resource="@color/notification_accent" />

</application>
```

There is no `appId` parameter on `initialize()` — Core reads `AppsonairAppId` from the manifest,
the same entry AppLink, AppSync, and AppRemark use, so you declare it once. If it is missing,
`initialize()` still completes and notifications still display, but registration cannot succeed.
React Native apps declare the same `meta-data` in `android/app/src/main/AndroidManifest.xml`.

> **Use a silhouette icon.** Android paints every non-transparent pixel of the small icon solid
> white, so a full-colour launcher icon renders as a white square. Supply a drawable that is
> transparent except for a white shape. Without the `default_notification_icon` entry the SDK
> falls back to your launcher icon and logs a warning.

### 3. Wire up your Activity

```kotlin
class MainActivity : AppCompatActivity(), PushListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppPushService.setListener(this)
        AppPushService.requestNotificationPermission(this)
        AppPushService.handleNotificationTapIntent(intent)   // cold-start taps
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { AppPushService.handleNotificationTapIntent(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppPushService.setListener(null)
    }
}
```

### 4. Implement the listener

```kotlin
override fun onTokenUpdated(token: String) {
    Log.d("MyApp", "FCM token: $token")
}

override fun onNotificationReceived(notification: PushNotification) {
    Log.d("MyApp", "Received: ${notification.title}")
}

override fun onNotificationOpened(notification: PushNotification) {
    Log.d("MyApp", "Opened: ${notification.id}")
}

override fun onError(error: PushError) {
    Log.e("MyApp", "[${error.code}] ${error.message}")
}
```

All `PushListener` methods have default empty bodies — override only what you need.

---

## Logging

Set the level before `initialize()`. Default is `NONE`.

```kotlin
AppPushService.Debug.logLevel = LogLevel.VERBOSE
AppPushService.initialize(this)
```

`NONE` · `FATAL` · `ERROR` · `WARN` · `INFO` · `DEBUG` · `VERBOSE`, in increasing verbosity.
Use `NONE` in production and `VERBOSE` during development.

---

## Identity

Associate the device with a known user after sign-in.

```kotlin
AppPushService.login("user_12345")
AppPushService.logout()   // clears externalId, tags, aliases — device reverts to anonymous
```

`logout()` deletes the subscription the user was attached to and registers a fresh anonymous
one in its place, so `PushUser.pushSubscription.id` changes. A failed delete leaves the
existing subscription untouched and is logged, not retried.

`logout()` deletes the subscription the user was attached to and registers a fresh anonymous
one in its place, so `PushUser.pushSubscription.id` changes. A failed delete leaves the
existing subscription untouched and is logged, not retried.

`externalId` persists across restarts, is readable via `PushUser.externalId`, and is
delivered to any registered `IUserStateObserver`. It is safe to call `login()` before the
device has registered — at startup, or straight after `logout()` — the SDK applies it once
the subscription exists.
delivered to any registered `IUserStateObserver`. It is safe to call `login()` before the
device has registered — at startup, or straight after `logout()` — the SDK applies it once
the subscription exists.

---

## Consent

Gate all data collection on explicit consent. Set `consentRequired` **before** `initialize()` so
it applies on the very first launch.

```kotlin
AppPushService.consentRequired = true
AppPushService.initialize(this)

AppPushService.consentGiven = true    // user accepted
AppPushService.consentGiven = false   // user withdrew
```

Both persist across restarts.

---

## Tags

Key/value pairs for audience segmentation.

```kotlin
AppPushService.User.addTag("plan", "premium")
AppPushService.User.addTags(mapOf("plan" to "premium", "region" to "us"))
AppPushService.User.removeTag("plan")
AppPushService.User.removeTags(listOf("plan", "trial_expiry"))

AppPushService.User.getTags { tags ->
    Log.d("MyApp", "Tags: $tags")   // main thread
}
```

Writes update local storage immediately, then sync to the backend fire-and-forget — a failure is
logged, not surfaced, because the local cache already holds the right value. `getTags` is the
exception: it fetches the server's copy and replaces the local cache before firing the callback.

There is no separate "update tag" call — `addTag` on an existing key overwrites it.

<details>
<summary>Backend calls</summary>

| Method | Call |
|---|---|
| `addTag` / `addTags` | `POST /subscriptions/{id}/tags` — `[{"key": "…", "value": "…"}]` |
| `removeTag` / `removeTags` | `POST /subscriptions/{id}/tags/remove` — `{"keys": ["…"]}` |
| `getTags` | `GET /subscriptions/{id}/tags` |

A bare `addTag`/`removeTag` sends a one-element array. All are no-ops (logged at `DEBUG`) before
the device has registered; `getTags` returns the local cache instead of hitting the network.

</details>

---

## Language

AppsOnAir localises **server-side** — the backend sends notifications already translated, so you
need no `strings.xml` keys. The device language and region ship in the registration payload
automatically (as `language` and `country`).

To override the detected language:

```kotlin
AppPushService.User.setLanguage("fr")          // or "en", "hi", "en-US"
val current = AppPushService.User.language
```

Updates the cache immediately, then `PATCH /subscriptions/{id}/language` in the background.

---

## Aliases & email

```kotlin
AppPushService.User.addAlias("crm_id", "CRM-9876")
AppPushService.User.addAliases(mapOf("crm_id" to "CRM-9876"))
AppPushService.User.removeAlias("crm_id")

AppPushService.User.addEmail("user@example.com")
AppPushService.User.removeEmail("user@example.com")
```

> SMS support is not in the push SDK scope and will arrive in a future release.

---

## Opt-in / opt-out

Stop or resume delivery without touching OS-level permission.

```kotlin
AppPushService.User.pushSubscription.optOut()
AppPushService.User.pushSubscription.optIn()

val optedIn = AppPushService.User.pushSubscription.optedIn   // local cache, synchronous
```

To read the server's copy — when it may have changed from another device or the console:

```kotlin
AppPushService.User.pushSubscription.getOptedIn { optedIn ->
    Log.d("MyApp", "Opted in: $optedIn")   // main thread; cache and observers already updated
}
```

Observe changes:

```kotlin
AppPushService.User.pushSubscription.addObserver(object : IPushSubscriptionObserver {
    override fun onPushSubscriptionDidChange(state: PushSubscriptionChangedState) {
        Log.d("MyApp", "opted-in: ${state.current.optedIn}")
    }
})
```

`optIn`/`optOut` call `POST /subscriptions/{id}/opt-in` and `/opt-out`; `getOptedIn` calls
`GET /subscriptions/{id}/opted-in`.

---

## Permission

```kotlin
val granted    = AppPushService.Notifications.permission(context)
val canRequest = AppPushService.Notifications.canRequestPermission(context)

AppPushService.Notifications.requestPermission(activity)

// Permanently denied on Android 13+? Send them to Settings instead:
AppPushService.Notifications.requestPermission(activity, fallbackToSettings = true)

AppPushService.Notifications.addPermissionObserver(object : INotificationPermissionObserver {
    override fun onNotificationPermissionDidChange(permission: Boolean) {
        Log.d("MyApp", "Permission: $permission")
    }
})
```

`canRequestPermission` returns `false` once permission is granted, once the user has permanently
denied it, and on Android 12 and below where no runtime permission exists.

---

## Foreground display

A data-only push arriving while the app is foregrounded fires `onNotificationReceived` but is
**not** shown as a system notification. Add a lifecycle listener to change that:

```kotlin
AppPushService.Notifications.addForegroundLifecycleListener(object : INotificationLifecycleListener {
    override fun onWillDisplay(event: NotificationWillDisplayEvent) {
        // Do nothing → the SDK displays it.
        // Call preventDefault() → suppressed entirely.
        event.preventDefault()
    }
})
```

---

## Handling taps

```kotlin
AppPushService.Notifications.addClickListener(object : INotificationClickListener {
    override fun onClick(event: NotificationClickEvent) {
        val actionId = event.result.actionId   // null = body tap, else action button ID
        val url      = event.result.url        // payload "url" key, if any
        Log.d("MyApp", "Tapped ${event.notification.title} action=$actionId")
    }
})
```

**Cold starts need one extra call.** When the app is killed, Android relaunches it with an
`Intent` carrying the notification data. Without `handleNotificationTapIntent`, that tap is
silently lost:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppPushService.handleNotificationTapIntent(intent)
}

override fun onNewIntent(intent: Intent?) {      // app alive in the back stack
    super.onNewIntent(intent)
    intent?.let { AppPushService.handleNotificationTapIntent(it) }
}
```

---

## Channels

Separate channels let users control notification types independently in Settings (Android 8.0+).

```kotlin
AppPushService.Notifications.createNotificationChannel(
    context     = applicationContext,
    id          = "transactional",
    name        = "Transactional Notifications",
    importance  = NotificationManager.IMPORTANCE_HIGH,
    description = "Order updates and alerts"
)

AppPushService.Notifications.deleteNotificationChannel(context, "transactional")
```

The SDK creates a default channel on first display: ID `appsonair_push_channel`, name
`Push Notifications`, importance `IMPORTANCE_HIGH`.

> A channel's sound is **fixed at creation**. To change it, use a new channel ID, delete the
> channel, or reinstall the app.

---

## Dismissing notifications

```kotlin
AppPushService.Notifications.clearAllNotifications(context)
AppPushService.Notifications.removeNotification(context, "order-4821")
AppPushService.Notifications.removeGroupedNotifications(context, groupKey = "orders")
```

`removeNotification` takes the **`notification_id` from the push payload** — the same value as
`PushNotification.id`, and nothing else:

```kotlin
override fun onNotificationReceived(n: PushNotification) {
    n.id?.let { AppPushService.Notifications.removeNotification(context, it) }
}
```


> **Two requirements.** Your backend must send `notification_id` — without it the notification
> cannot be addressed. And only notifications **this SDK displayed** can be removed: a payload
> with a `notification` block received while backgrounded is drawn by Firebase under an internal
> ID the SDK never sees. Send data-only payloads if you need this to work.

---

## Badge count

```kotlin
AppPushService.setBadgeCount(context, 5)
AppPushService.clearBadgeCount(context)   // same as setBadgeCount(context, 0)
```

Android has no standard badge API, so the SDK targets the well-known OEM launchers. Every attempt
is wrapped in `runCatching` — unsupported launchers are skipped silently, never a crash.

<details>
<summary>Supported launchers</summary>

| Launcher | Mechanism |
|---|---|
| Samsung | Broadcast `android.intent.action.BADGE_COUNT_UPDATE` |
| Huawei (EMUI) | `ContentResolver.call()` to the launcher settings provider |
| Xiaomi (MIUI) | Broadcast `android.intent.action.APPLICATION_MESSAGE_UPDATE` |
| Sony | `ContentResolver.insert()` to the Sony badge provider |
| ASUS ZenUI | Broadcast `com.asus.badge.ACTION_BADGE_COUNT_UPDATE` |

</details>

---

## Rich media

A data-only push received in the background is built and displayed by the SDK. Include
`image_url` for a picture notification:

```json
{
  "message": {
    "token": "device-fcm-token",
    "data": {
      "notification_id": "notif-123",
      "title":           "New offer",
      "body":            "Limited time deal — tap to view",
      "image_url":       "https://cdn.example.com/offer.jpg"
    }
  }
}
```

The SDK downloads the image and renders `BigPictureStyle`, using it as both the expanded picture
and the large icon. If the URL is absent or the download fails, it falls back to `BigTextStyle`.

To display one yourself:

```kotlin
import com.appsonair.apppush.notification.PushNotificationHelper

PushNotificationHelper.show(context, pushNotification)

val intent = Intent(context, ProductActivity::class.java)
    .putExtra("product_id", "abc-123")
PushNotificationHelper.show(context, pushNotification, launchIntent = intent)
```

---

## Test device

Marks the device so it receives pushes sent from the AppsOnAir console's Test mode without
affecting real users. Persisted, and sent in the registration payload as `is_test_device`.

```kotlin
AppPushService.isTestDevice = true
```

---

## Payload reference

A minimal push:

```json
{
  "message": {
    "token": "device-fcm-token",
    "notification": { "title": "Hello", "body": "Push is working!" },
    "data":         { "notification_id": "notif-abc-123" }
  }
}
```

> **Prefer data-only payloads** (no `notification` block) whenever you want the SDK to control
> display. They guarantee `onMessageReceived` fires in every app state; a `notification` block

### Data keys

| Key | Meaning |
|---|---|
| `notification_id` | Unique ID, surfaced as `PushNotification.id`. **Required** to dismiss the notification later. |
| `title` / `body` | Pre-translated text. Falls back to the `notification` block if absent. |
| `image_url` | HTTPS JPEG/PNG to display as `BigPictureStyle`. |
| `channel_id` | Target channel. Defaults to `appsonair_push_channel`. |
| `collapse_key` | A newer notification with the same key replaces the previous one instead of stacking. |
| `sound` | File in `res/raw` without extension (`"chime"` → `res/raw/chime.wav`). Falls back to the default sound, logging a warning, if missing. |
| `actions` | Up to 3 buttons: `"[{\"id\":\"reply\",\"title\":\"Reply\"}]"`. Fires the click listener with `result.actionId` set. Only `id` and `title` are read. A payload carrying this key is always rendered by the SDK, so the buttons survive whether or not it also has an FCM `notification` block. |
| `actions` | Up to 3 buttons: `"[{\"id\":\"reply\",\"title\":\"Reply\"}]"`. Fires the click listener with `result.actionId` set. Only `id` and `title` are read. A payload carrying this key is always rendered by the SDK, so the buttons survive whether or not it also has an FCM `notification` block. |

<details>
<summary>Why a custom sound sometimes does nothing</summary>

`sound` only reaches notifications **the SDK builds** — data-only payloads in any app state,
any payload while foregrounded, and payloads carrying `actions`. Firebase renders everything
else itself and ignores the key on Android 8+, where sound belongs to the channel.

The file lives in the **host app**, not the SDK: `app/src/main/res/raw/chime.mp3`, referenced as
`"sound": "chime"` with no extension. A channel's sound cannot be changed once created, so the
SDK gives each sound its own channel (`<channel_id>_snd_<sound>`) — pointing an existing sound
name at a different file keeps playing the old one until the app is reinstalled.
`sound` only reaches notifications **the SDK builds** — data-only payloads in any app state,
any payload while foregrounded, and payloads carrying `actions`. Firebase renders everything
else itself and ignores the key on Android 8+, where sound belongs to the channel.

The file lives in the **host app**, not the SDK: `app/src/main/res/raw/chime.mp3`, referenced as
`"sound": "chime"` with no extension. A channel's sound cannot be changed once created, so the
SDK gives each sound its own channel (`<channel_id>_snd_<sound>`) — pointing an existing sound
name at a different file keeps playing the old one until the app is reinstalled.


```kotlin
AppPushService.Notifications.createNotificationChannel(
    context = this,
    id      = "promotions",
    name    = "Promotions",
    sound   = "chime"        // res/raw/chime.wav
)
```

The reliable alternative is a data-only payload with `"sound"` in `data` — the SDK then renders
it and creates a per-sound channel automatically.

</details>

---

## API reference

### `AppPushService`

| Member | Description |
|---|---|
| `initialize(context, debug = false)` | Call first, in `Application.onCreate()`. `debug` is a deprecated shortcut for `LogLevel.DEBUG` — prefer `Debug.logLevel`. |
| `setListener(listener)` | Register a `PushListener`. One at a time; `null` removes it. |
| `requestNotificationPermission(activity)` | Request `POST_NOTIFICATIONS` on Android 13+. No-op otherwise or if already granted. |
| `handleNotificationTapIntent(intent)` | Route a cold-start or back-stack tap. Call from `onCreate` **and** `onNewIntent`. |
| `getDeviceId(): String` | Stable device UUID from Core. Survives restarts, cleared on uninstall. |
| `refreshFcmToken()` | Fetch the latest token. Automatic on `initialize()` and on refresh. |
| `getInstallationId()` | Fetch the Firebase Installation ID; delivered via `onInstallationIdUpdated`. |
| `setSubscriptionId(id)` | Store a backend-assigned subscription ID. Normally unnecessary — see [Registration](#registration). |
| `isTestDevice: Boolean` | Mark as a test device. Persisted, default `false`. |
| `login(externalId)` / `logout()` | Associate or clear the user identity. |
| `consentRequired` / `consentGiven` | GDPR gating. Set `consentRequired` before `initialize()`. |
| `isPermissionGranted(context)` | Same result as `Notifications.permission(context)` — either is fine. |
| `clearAllNotifications(context)` | Dismiss everything this app posted. `Notifications.clearAllNotifications` calls through to this. |
| `setBadgeCount(context, count)` / `clearBadgeCount(context)` | App icon badge. |

### `AppPushService.User` · `PushUser`

| Member | Description |
|---|---|
| `appsOnAirId: String` | Device ID — same value as `getDeviceId()`. |
| `externalId: String?` | Set by `login()`, `null` when anonymous. |
| `language: String` / `setLanguage(code)` | Read or override the language. Setter syncs via `PATCH …/language`. |
| `addTag(key, value)` / `addTags(map)` | Add or update tags. Syncs via `POST …/tags`. |
| `removeTag(key)` / `removeTags(keys)` | Remove tags. Syncs via `POST …/tags/remove`. |
| `getTags(callback)` | Fetch the backend's copy, replace the cache, fire on the main thread. |
| `addAlias(label, id)` / `addAliases(map)` / `removeAlias(label)` / `removeAliases(labels)` | External identifier aliases. |
| `addEmail(address)` / `removeEmail(address)` | Email channel. |
| `addObserver(IUserStateObserver)` / `removeObserver(…)` | Observe `login`/`logout`. |
| `pushSubscription.id: String?` | Backend subscription ID, `null` until registered. |
| `pushSubscription.token: String?` | Current FCM token. |
| `pushSubscription.optedIn: Boolean` | Local cache, synchronous. |
| `pushSubscription.optIn()` / `optOut()` | Resume or pause delivery. |
| `pushSubscription.getOptedIn(callback)` | Fetch the backend's copy of the opt-in state. |
| `pushSubscription.addObserver(IPushSubscriptionObserver)` / `removeObserver(…)` | Observe subscription changes. |

### `AppPushService.Notifications` · `PushNotifications`

| Member | Description |
|---|---|
| `permission(context): Boolean` | Current permission state. |
| `canRequestPermission(context): Boolean` | Whether the OS dialog can still be shown. |
| `requestPermission(activity, fallbackToSettings = false)` | Show the dialog; optionally open Settings when denied. |
| `addPermissionObserver(…)` / `removePermissionObserver(…)` | Observe permission changes. |
| `addForegroundLifecycleListener(…)` / `removeForegroundLifecycleListener(…)` | Control foreground display. |
| `addClickListener(…)` / `removeClickListener(…)` | Observe taps and action buttons. |
| `createNotificationChannel(context, id, name, importance, description)` | Create a channel (8.0+). No-op if it exists. |
| `deleteNotificationChannel(context, id)` | Delete a channel. |
| `clearAllNotifications(context)` | Dismiss everything this app posted. |
| `removeNotification(context, notificationId)` | Dismiss one, by payload `notification_id`. |
| `removeGroupedNotifications(context, groupKey)` | Dismiss a group. |

### `AppPushService.Debug` · `PushDebug`

| Member | Description |
|---|---|
| `logLevel: LogLevel` | Default `NONE`. Assign before `initialize()`. |
| `setLogLevel(level)` | Java-friendly setter. |

### `PushNotificationHelper`

| Member | Description |
|---|---|
| `show(context, notification, launchIntent?, channelId?)` | Build and display. Automatic for background data-only pushes. |
| `ensureChannel(context, channelId?, channelName?, soundUri?)` | Create the default channel (8.0+). |

---

## Registration

`initialize()` fetches the FCM token and registers the device automatically —
`POST /subscriptions` — then stores the returned ID. **No app-side work is required.**

To read the ID once it arrives, or react to it:

```kotlin
val id = AppPushService.User.pushSubscription.id   // null until registration completes

AppPushService.User.pushSubscription.addObserver(object : IPushSubscriptionObserver {
    override fun onPushSubscriptionDidChange(state: PushSubscriptionChangedState) {
        Log.d("MyApp", "subscription: ${AppPushService.User.pushSubscription.id}")
    }
})
```

Every later change patches that subscription rather than re-registering — token rotation,
`login`/`logout`, permission toggles, tags, language, and opt-in state. The ID accompanies every
event report so the backend can attribute events to the right subscriber.

Call `setSubscriptionId()` only if your own backend issues the ID instead.

---

## Types

```kotlin
interface PushListener {
    fun onTokenUpdated(token: String) {}
    fun onInstallationIdUpdated(id: String) {}
    fun onNotificationReceived(notification: PushNotification) {}
    fun onNotificationOpened(notification: PushNotification) {}
    fun onError(error: PushError) {}
}

data class PushNotification(
    val id: String?,               // "notification_id" from the payload
    val title: String?,
    val body: String?,
    val data: Map<String, String>, // full FCM data payload
    val imageUrl: String? = null,
    val sound: String? = null      // res/raw file name, no extension
)
```

### Error codes

| Code | Meaning | Fix |
|---|---|---|
| `NOT_INITIALIZED` | Called before `initialize()` | Initialize in `Application.onCreate()` |
| `FIREBASE_NOT_CONFIGURED` | No `google-services.json` / plugin not applied | Complete [Firebase setup](#firebase-setup) and rebuild |
| `TOKEN_FETCH_FAILED` | FCM token fetch failed | Check SHA-1, API key restrictions |
| `INSTALLATION_ID_FETCH_FAILED` | Installation ID fetch failed | Enable the Firebase Installations API |
| `PERMISSION_DENIED` | Notification permission denied | Guide the user to Settings |
| `UNKNOWN` | Unexpected | Inspect `error.cause` |

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `FIS_AUTH_ERROR` in Logcat | SHA-1 missing, or API key restricted | Add the fingerprint, enable Firebase Installations API |
| Token never arrives | Firebase not initialised | Confirm `google-services.json` sits in `app/` |
| `JVM-target mismatch` at build | SDK and app targets differ | Set both to `VERSION_17` |
| Token split across log lines | Logcat truncates | Concatenate the `[0]` and `[1]` lines |
| No push in background | `notification` block handled by the system | Send a data-only payload |
| Image missing | URL not HTTPS, or download timed out | Use HTTPS; the image must respond within 10 s |
| Cold-start tap ignored | `handleNotificationTapIntent` not called | Add it to `onCreate` and `onNewIntent` |
| Foreground notification not shown | No lifecycle listener, or `preventDefault()` called | Add the listener; don't call `preventDefault()` |
| White square instead of an icon | Full-colour drawable used | Supply a transparent silhouette |

---

## Security

Never ship these in your app — they belong on your server:

- Firebase service account JSON or private key
- FCM server key (legacy)
- AppsOnAir backend secret keys
