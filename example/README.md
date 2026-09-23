# AppsOnAir Push — Example App

A minimal app wired to the SDK in this repository. It builds `:push` from source, so changes to
the SDK show up here immediately without publishing anything.

## Run it

**1. Add your `google-services.json`** to `example/app/`. Without it the build stops with
`File google-services.json is missing`. The file is gitignored — it belongs to your Firebase
project, not to this repo. See the [Firebase setup](../README.md#firebase-setup) section.

**2. Set your app ID** in `app/src/main/AndroidManifest.xml`:

```xml
<meta-data
    android:name="AppsonairAppId"
    android:value="YOUR_APP_ID" />
```

Leave it as `YOUR_APP_ID` and notifications still display, but the SDK cannot register a
subscription — so tags, identity, and opt-in have no backend to talk to.

**3. Build and install** from this directory:

```bash
cd example
./gradlew :app:installDebug
```

## What it demonstrates

| File | |
|---|---|
| `ExampleApp.kt` | `initialize()` in `Application.onCreate()`, log level set beforehand |
| `MainActivity.kt` | Listener registration, permission, identity, tags, opt-in/out, badge, and tap handling |

`MainActivity` shows the two calls that are easy to miss:

- **`handleNotificationTapIntent(intent)` in `onCreate`** — a tap that cold-starts the app
  arrives on the launch `Intent`; without this the tap is dropped.
- **the same call in `onNewIntent`** — when the app is already in the back stack, Android
  delivers the tap there instead.

Everything the SDK reports back lands in the on-screen log, so you can send a push from the
console and watch it arrive.

## Why this is a separate Gradle build

`example/` has its own `settings.gradle.kts` and is **not** part of the SDK's root build.
JitPack builds the repository root, and this example applies the `google-services` plugin, which
fails without a `google-services.json`. Keeping the example out of the root build means a config
file that cannot be committed can never break the SDK's publish.

The SDK is consumed as a local module:

```kotlin
include(":push")
project(":push").projectDir = file("../push")
```

A consumer of the published artifact would instead write:

```kotlin
implementation("com.github.apps-on-air:appsonair-android-push-notification:1.0.1-beta")
```
