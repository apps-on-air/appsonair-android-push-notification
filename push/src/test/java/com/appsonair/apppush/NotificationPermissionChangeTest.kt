package com.appsonair.apppush

import android.app.Activity
import android.app.NotificationManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import com.appsonair.apppush.services.PushApiService
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.time.Duration
import java.util.UUID

/**
 * The POST_NOTIFICATIONS dialog is a dialog-themed Activity: it PAUSES the host Activity but
 * never stops it, so the process stays foregrounded and ProcessLifecycleOwner.onStart — the
 * SDK's only permission-change trigger before this fix — never fires again. A grant made there
 * went unreported until the next cold start, which is the "force-close and reopen" symptom.
 *
 * Every test below drives pause() → resume(), which is exactly the lifecycle the dialog
 * produces. Reverting the checkPermissionChange() call in AppPushService.onActivityResumed makes
 * every test here fail except [resumeWithoutPermissionChange_sendsNothing].
 *
 * Scope: ProcessLifecycleOwner stays at INITIALIZED under Robolectric — the androidx initializer
 * that feeds it Activity callbacks is a ContentProvider that does not run here — so
 * PushSessionManager.onStart never fires and the SessionManager trigger is NOT covered.
 * These tests exercise the Activity-resume trigger only, which is the one the fix adds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPermissionChangeTest {

    private companion object {
        val VOLUME_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    }

    private lateinit var context: Context
    private lateinit var controller: ActivityController<Activity>

    /**
     * Every request the SDK would have put on the wire, in order. Body is `Any` — `JSONObject`
     * for every request here (these tests only exercise the permission PATCH); the tags POST
     * elsewhere in the SDK sends a `JSONArray`.
     */
    private val requests = mutableListOf<Triple<String, String, Any>>()

    private val patches get() = requests.filter { it.first == "PATCH" }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // initialize() logs the device id, which builds Core's DeviceInfoService, which reads
        // the device's storage figures in its constructor. Neither system service answers under
        // Robolectric until it is given data: getPrimaryStorageVolume() indexes into an empty
        // volume list, and getTotalBytes() throws for an unknown storage UUID.
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        shadowOf(storageManager).addStorageVolume(
            StorageVolumeBuilder(
                "primary",
                context.filesDir,
                "Internal storage",
                Process.myUserHandle(),
                "mounted"
            ).setIsPrimary(true).setFsUuid(VOLUME_UUID.toString()).build()
        )
        val storageStats =
            context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        // Core reads primaryStorageVolume.uuid and falls back to UUID_DEFAULT when it is null.
        // Registering both leaves the test independent of which branch it takes.
        listOf(VOLUME_UUID, StorageManager.UUID_DEFAULT).forEach {
            shadowOf(storageStats)
                .setStorageDeviceFreeAndTotalBytes(it, 512L * 1024 * 1024, 1024L * 1024 * 1024)
        }

        // initialize() ends in refreshFcmToken(), and FirebaseMessaging.getInstance() throws
        // without a default FirebaseApp. There is no google-services.json in a unit test, so
        // supply the minimum options by hand.
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:000000000000:android:0000000000000000")
                    .setApiKey("test-api-key")
                    .setProjectId("appsonair-test")
                    .build()
            )
        }

        // AppPushService is an object: state survives between tests in the same sandbox.
        AppPushService.previousPermission = null
        AppPushService.permissionObservers.clear()
        requests.clear()

        PushApiService.transport = { method, path, body, onResult ->
            requests += Triple(method, path, body)
            onResult(PushApiService.Result.Success(JSONObject()))
        }

        setNotificationsEnabled(false)
        AppPushService.initialize(context)
        // SessionManager.start() registers the ProcessLifecycleOwner observer from a main-thread
        // post, and Robolectric's looper does not run it until idled.
        shadowOf(Looper.getMainLooper()).idle()
        // A PATCH needs a subscription id; the real one arrives with the POST /subscriptions
        // response, which registration is not asked to perform here.
        AppPushService.subscriptionId = "sub-test-1"

        controller = Robolectric.buildActivity(Activity::class.java).setup()
        // setup() records the baseline (enabled=false) and must not report anything itself.
        requests.clear()
    }

    @After
    fun tearDown() {
        PushApiService.transport = null
        AppPushService.permissionObservers.clear()
    }

    private fun setNotificationsEnabled(enabled: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(enabled)
    }

    /** The permission dialog: the Activity pauses and resumes, the process never backgrounds. */
    private fun grantPermissionViaSystemDialog(enabled: Boolean = true) {
        controller.pause()
        setNotificationsEnabled(enabled)
        controller.resume()
    }

    // MARK: - The reported bug

    @Test
    fun grantedWhileForegrounded_patchesEnabledTrue() {
        grantPermissionViaSystemDialog()

        assertEquals("expected exactly one PATCH, got $requests", 1, patches.size)
        val (_, path, body) = patches.single()
        assertEquals("subscriptions/sub-test-1", path)
        // The change endpoint takes the one changed field: {"enabled": true}
        val bodyObj = body as JSONObject
        assertEquals(1, bodyObj.length())
        assertEquals(true, bodyObj.get("enabled"))
    }

    @Test
    fun grantedWhileForegrounded_notifiesPermissionObserver() {
        val observed = mutableListOf<Boolean>()
        AppPushService.Notifications.addPermissionObserver(
            object : INotificationPermissionObserver {
                override fun onNotificationPermissionDidChange(permission: Boolean) {
                    observed += permission
                }
            }
        )

        grantPermissionViaSystemDialog()

        assertEquals(listOf(true), observed)
    }

    // MARK: - No spurious traffic

    @Test
    fun resumeWithoutPermissionChange_sendsNothing() {
        controller.pause()
        controller.resume()

        assertTrue("expected no request, got $requests", requests.isEmpty())
    }

    @Test
    fun repeatedResumesAfterOneGrant_patchOnlyOnce() {
        grantPermissionViaSystemDialog()
        controller.pause()
        controller.resume()
        controller.pause()
        controller.resume()

        assertEquals("expected one PATCH across three resumes, got $requests", 1, patches.size)
    }

    @Test
    fun grantedWhileStopped_patchesOnceOnReturn() {
        // The Settings deep link from requestPermission(fallbackToSettings = true) is a full
        // Activity, so the host Activity stops before the permission changes. Returning must
        // report the change exactly once.
        controller.pause()
        controller.stop()
        // ProcessLifecycleOwner delays ON_STOP by 700ms to ride out configuration changes.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        setNotificationsEnabled(true)

        controller.start()
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("expected a single PATCH on return, got $requests", 1, patches.size)
        assertEquals(true, (patches.single().third as JSONObject).get("enabled"))
    }

    // MARK: - The reverse transition

    @Test
    fun revokedWhileForegrounded_patchesEnabledFalse() {
        grantPermissionViaSystemDialog()
        requests.clear()

        // The transition is symmetric — the backend's enabled flag has to follow the permission
        // down as well as up, and a revocation reaches the SDK through the same resume.
        grantPermissionViaSystemDialog(enabled = false)

        assertEquals(1, patches.size)
        assertEquals(false, (patches.single().third as JSONObject).get("enabled"))
    }
}
