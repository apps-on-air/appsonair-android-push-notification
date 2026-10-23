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
import com.appsonair.apppush.services.PushSubscriptionService
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
import java.util.UUID

/**
 * On a first install there is no subscription id until POST /subscriptions answers, and every
 * update made before then is skipped. A change made while that POST is in flight — the user
 * tapping Allow on the permission dialog the app showed at launch — was therefore lost: the
 * payload had already been built with the old value, the PATCH was skipped, and nothing sent it
 * again until the next cold start. [grantedDuringFirstRegistration_patchesEnabledOnceRegistered]
 * is that report.
 *
 * Every test holds the POST open, makes a change inside that window, then lets it answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChangesDuringRegistrationTest {

    private companion object {
        val VOLUME_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
        const val SUBSCRIPTION_ID = "sub-first-1"
    }

    private lateinit var context: Context
    private lateinit var controller: ActivityController<Activity>

    /** Every request the SDK put on the wire, in order: method, path, body. */
    private val requests = mutableListOf<Triple<String, String, Any>>()

    /** Answers the held POST /subscriptions. Null until one is sent, and after it answers. */
    private var answerRegistration: (() -> Unit)? = null

    /** Set by [startRegistration]; only the POST it sends is held. */
    private var registering = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // initialize() builds Core's DeviceInfoService, which reads storage figures that
        // Robolectric only answers once given data.
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
        listOf(VOLUME_UUID, StorageManager.UUID_DEFAULT).forEach {
            shadowOf(storageStats)
                .setStorageDeviceFreeAndTotalBytes(it, 512L * 1024 * 1024, 1024L * 1024 * 1024)
        }

        // initialize() ends in refreshFcmToken(), and FirebaseMessaging.getInstance() throws
        // without a default FirebaseApp.
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
        answerRegistration = null
        registering = false

        PushApiService.transport = transport@{ method, path, body, onResult ->
            // The FCM fetch initialize() starts fails here, and the SDK's once-per-process
            // cached-token fallback then registers on its own from whichever looper idle runs
            // the callback. Refusing it leaves startRegistration() the only registration.
            if (method == "POST" && path == "subscriptions" && !registering) {
                onResult(PushApiService.Result.Failure("not this test's registration", false))
                return@transport
            }
            requests += Triple(method, path, body)
            if (method == "POST" && path == "subscriptions") {
                registering = false
                answerRegistration = {
                    answerRegistration = null
                    onResult(
                        PushApiService.Result.Success(
                            JSONObject().put("subscriptionId", SUBSCRIPTION_ID),
                            200
                        )
                    )
                }
            } else {
                onResult(PushApiService.Result.Success(JSONObject()))
            }
        }

        setNotificationsEnabled(false)
        AppPushService.initialize(context)
        shadowOf(Looper.getMainLooper()).idle()

        // A first install: a token to register with, and nothing registered yet.
        AppPushService.storage.prefs.edit().clear().commit()
        AppPushService.storage.saveFcmToken("fcm-token-1")
        AppPushService.subscriptionId = null
        AppPushService.externalId = null
        AppPushService.isOptedOut = false
        AppPushService.emails.clear()
        AppPushService.language = "en"

        // Records the permission baseline (disabled), as the launch Activity does.
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        requests.clear()
    }

    @After
    fun tearDown() {
        // register() refuses to start while one is in flight, and that flag outlives the test.
        answerRegistration?.invoke()
        PushApiService.transport = null
        AppPushService.permissionObservers.clear()
        AppPushService.emails.clear()
        AppPushService.storage.prefs.edit().clear().commit()
    }

    private fun setNotificationsEnabled(enabled: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(enabled)
    }

    /** The permission dialog: the Activity pauses and resumes, the process never backgrounds. */
    private fun answerPermissionDialog(enabled: Boolean) {
        controller.pause()
        setNotificationsEnabled(enabled)
        controller.resume()
    }

    /** Sends POST /subscriptions and leaves it unanswered. */
    private fun startRegistration() {
        registering = true
        PushSubscriptionService.register(context, "token")
        assertTrue("expected the POST to be held", answerRegistration != null)
    }

    private fun registrationBody() =
        requests.single { it.first == "POST" && it.second == "subscriptions" }.third as JSONObject

    /** Everything sent after the registration POST. */
    private fun followUps() = requests.dropWhile { it.second != "subscriptions" }.drop(1)

    // MARK: - The reported bug

    @Test
    fun grantedDuringFirstRegistration_patchesEnabledOnceRegistered() {
        startRegistration()
        assertEquals(false, registrationBody().get("enabled"))

        answerPermissionDialog(enabled = true)
        assertTrue("no subscription to PATCH yet, got $requests", followUps().isEmpty())

        answerRegistration!!()

        val (method, path, body) = followUps().single()
        assertEquals("PATCH", method)
        assertEquals("subscriptions/$SUBSCRIPTION_ID", path)
        assertEquals(true, (body as JSONObject).get("enabled"))
    }

    @Test
    fun grantedBeforeTheObserverSawIt_isStillSynced() {
        // A change the resume observer never reported — only the live permission read catches it.
        startRegistration()
        setNotificationsEnabled(true)

        answerRegistration!!()

        val (_, path, body) = followUps().single()
        assertEquals("subscriptions/$SUBSCRIPTION_ID", path)
        assertEquals(true, (body as JSONObject).get("enabled"))
    }

    // MARK: - No spurious traffic

    @Test
    fun nothingChangedDuringRegistration_sendsOnlyThePost() {
        startRegistration()
        answerRegistration!!()

        assertEquals("expected the POST alone, got $requests", 1, requests.size)
    }

    @Test
    fun grantedBeforeRegistration_isCarriedByThePayload() {
        answerPermissionDialog(enabled = true)
        requests.clear()

        startRegistration()
        assertEquals(true, registrationBody().get("enabled"))
        answerRegistration!!()

        assertEquals("expected the POST alone, got $requests", 1, requests.size)
    }

    @Test
    fun grantedThenRevokedDuringRegistration_sendsNothing() {
        startRegistration()
        answerPermissionDialog(enabled = true)
        answerPermissionDialog(enabled = false)

        answerRegistration!!()

        assertTrue("payload already matches, got ${followUps()}", followUps().isEmpty())
    }

    // MARK: - Other payload fields in the same window

    @Test
    fun tokenRotatedDuringRegistration_patchesNewToken() {
        startRegistration()
        AppPushService.handleRotatedToken("fcm-token-2")

        answerRegistration!!()

        val (method, path, body) = followUps().single()
        assertEquals("PATCH", method)
        assertEquals("subscriptions/$SUBSCRIPTION_ID", path)
        assertEquals("fcm-token-2", (body as JSONObject).get("push_token"))
    }

    @Test
    fun freshTokenWhileCachedTokenRegistrationInFlight_patchesNewToken() {
        // The cached-token fallback registers first; the fetch that succeeds later hands over a
        // new token, and its own register() is skipped because the first is still in flight.
        startRegistration()
        AppPushService.handleFcmToken("fcm-token-2")
        assertEquals(1, requests.count { it.second == "subscriptions" })

        answerRegistration!!()

        val body = followUps().single().third as JSONObject
        assertEquals("fcm-token-2", body.get("push_token"))
    }

    @Test
    fun languageSetDuringRegistration_isSentOnceRegistered() {
        startRegistration()
        PushUser.setLanguage("fr")

        answerRegistration!!()

        val (method, path, body) = followUps().single()
        assertEquals("PATCH", method)
        assertEquals("subscriptions/$SUBSCRIPTION_ID/language", path)
        assertEquals("fr", (body as JSONObject).get("language"))
    }

    @Test
    fun optedOutDuringRegistration_isSentOnceRegistered() {
        startRegistration()
        PushUser.PushSubscription.optOut()

        answerRegistration!!()

        val (method, path, _) = followUps().single()
        assertEquals("POST", method)
        assertEquals("subscriptions/$SUBSCRIPTION_ID/opt-out", path)
    }

    // MARK: - Fields the payload never carries

    @Test
    fun emailSetBeforeFirstRegistration_isAppliedWhenTheSubscriptionArrives() {
        PushUser.addEmail("user@example.com")
        assertTrue("nothing to PATCH without a subscription, got $requests", requests.isEmpty())

        startRegistration()
        answerRegistration!!()

        val (method, path, body) = followUps().single()
        assertEquals("PATCH", method)
        assertEquals("subscriptions/$SUBSCRIPTION_ID", path)
        assertEquals("user@example.com", (body as JSONObject).get("email"))
    }

    @Test
    fun reRegisteringTheSameSubscription_doesNotReapplyEmail() {
        AppPushService.subscriptionId = SUBSCRIPTION_ID
        PushUser.addEmail("user@example.com")
        requests.clear()

        startRegistration()
        answerRegistration!!()

        assertEquals("expected the POST alone, got $requests", 1, requests.size)
    }
}
