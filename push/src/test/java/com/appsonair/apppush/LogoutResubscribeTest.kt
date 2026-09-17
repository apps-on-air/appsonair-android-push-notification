package com.appsonair.apppush

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.util.UUID

/**
 * logout() detaches the user by deleting the subscription that carried the external id, then
 * registering a fresh anonymous one — it does not PATCH external_id to null.
 *
 * The re-registration is the part that silently breaks: the payload POSTed after a logout is
 * byte-for-byte the one already registered, so register()'s unchanged-check would skip it unless
 * the delete clears the stored fingerprint. [logout_deletesThenRegistersNewSubscription] fails
 * if that clearing is dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogoutResubscribeTest {

    private companion object {
        val VOLUME_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
        const val OLD_SUBSCRIPTION_ID = "sub-old-1"
        const val NEW_SUBSCRIPTION_ID = "sub-new-2"
    }

    private lateinit var context: Context

    /** Every request the SDK put on the wire, in order: method, path, body. */
    private val requests = mutableListOf<Triple<String, String, Any>>()

    /** Set per-test to fail the DELETE; the POST always succeeds. */
    private var deleteSucceeds = true

    /** The id the POST hands back — the same one models an upsert of an unchanged device. */
    private var postReturnsId = NEW_SUBSCRIPTION_ID

    /**
     * Holds the DELETE response open so a test can act inside the logout → re-register window,
     * which is where the account-switch bug lives. [releaseDelete] closes it.
     */
    private var deferDelete = false
    private var releaseDelete: (() -> Unit)? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // initialize() logs the device id, which builds Core's DeviceInfoService, which reads
        // the device's storage figures in its constructor — neither system service answers
        // under Robolectric until it is given data.
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
        AppPushService.userStateObservers.clear()
        requests.clear()
        deleteSucceeds = true
        postReturnsId = NEW_SUBSCRIPTION_ID
        deferDelete = false
        releaseDelete = null

        PushApiService.transport = { method, path, body, onResult ->
            requests += Triple(method, path, body)
            when {
                method == "DELETE" && !deleteSucceeds ->
                    onResult(PushApiService.Result.Failure("HTTP 500", true))

                method == "DELETE" && deferDelete -> {
                    releaseDelete = { onResult(PushApiService.Result.Success(JSONObject())) }
                }

                method == "POST" ->
                    onResult(
                        PushApiService.Result.Success(
                            JSONObject().put("subscriptionId", postReturnsId)
                        )
                    )

                else -> onResult(PushApiService.Result.Success(JSONObject()))
            }
        }

        AppPushService.initialize(context)
        shadowOf(Looper.getMainLooper()).idle()

        // The state a logged-in, already-registered device is in: a token (register() returns
        // early without one), a subscription id, and the fingerprint of the payload that
        // created it.
        AppPushService.storage.saveFcmToken("fcm-token-test")
        AppPushService.subscriptionId = OLD_SUBSCRIPTION_ID
        AppPushService.login("user_12345")
        requests.clear()
    }

    @After
    fun tearDown() {
        PushApiService.transport = null
        AppPushService.userStateObservers.clear()
        AppPushService.storage.prefs.edit().clear().commit()
    }

    @Test
    fun logout_deletesThenRegistersNewSubscription() {
        // register() only skips when the stored fingerprint matches, so record one first —
        // otherwise the POST below would go out whether or not the delete cleared it.
        AppPushService.storage.putString("last_registration_hash", currentFingerprint())

        AppPushService.logout()

        assertEquals("expected DELETE then POST, got $requests", 2, requests.size)

        val (deleteMethod, deletePath, _) = requests[0]
        assertEquals("DELETE", deleteMethod)
        assertEquals("subscriptions/$OLD_SUBSCRIPTION_ID", deletePath)

        val (postMethod, postPath, _) = requests[1]
        assertEquals("POST", postMethod)
        assertEquals("subscriptions", postPath)

        assertEquals(NEW_SUBSCRIPTION_ID, AppPushService.subscriptionId)
    }

    @Test
    fun logout_registersWithoutExternalId() {
        AppPushService.logout()

        val body = requests.single { it.first == "POST" }.third as JSONObject
        assertNull(AppPushService.externalId)
        // The registration payload has never carried external_id; login() PATCHes it on
        // afterwards. This pins that the replacement subscription starts anonymous.
        assertEquals(false, body.has("external_id"))
    }

    @Test
    fun logoutWithFailedDelete_keepsSubscriptionAndDoesNotRegister() {
        deleteSucceeds = false

        AppPushService.logout()

        assertEquals("expected only the DELETE, got $requests", 1, requests.size)
        assertEquals("DELETE", requests.single().first)
        assertEquals(OLD_SUBSCRIPTION_ID, AppPushService.subscriptionId)
    }

    @Test
    fun logoutWithoutSubscription_sendsNothing() {
        AppPushService.subscriptionId = null

        AppPushService.logout()

        assertEquals("expected no request, got $requests", 0, requests.size)
    }

    // MARK: - external_id survives a subscription swap

    /**
     * The account switch: logout() followed straight away by login(). The external id is not
     * in the registration payload and login() can only PATCH a subscription that exists, so
     * without the re-apply in register() the new user never reaches the backend — the PATCH
     * login() fires here lands on the subscription the DELETE is about to destroy.
     */
    @Test
    fun loginInsideLogoutWindow_reachesTheNewSubscription() {
        deferDelete = true
        AppPushService.logout()
        AppPushService.login("user_67890")

        // The only PATCH so far went to the subscription that is about to be deleted.
        assertEquals("subscriptions/$OLD_SUBSCRIPTION_ID", patches().single().second)

        releaseDelete!!()

        val last = patches().last()
        assertEquals("subscriptions/$NEW_SUBSCRIPTION_ID", last.second)
        assertEquals("user_67890", (last.third as JSONObject).getString("external_id"))
        assertEquals("user_67890", AppPushService.externalId)
    }

    /** The same gap at startup: login() before the first registration had nothing to PATCH. */
    @Test
    fun loginBeforeFirstRegistration_isAppliedWhenTheSubscriptionArrives() {
        AppPushService.subscriptionId = null
        AppPushService.storage.remove("last_registration_hash")
        requests.clear()

        AppPushService.login("user_99")
        assertTrue("nothing to PATCH without a subscription, got $requests", patches().isEmpty())

        PushSubscriptionService.register(context, "test")

        val patch = patches().single()
        assertEquals("subscriptions/$NEW_SUBSCRIPTION_ID", patch.second)
        assertEquals("user_99", (patch.third as JSONObject).getString("external_id"))
    }

    /** An unchanged device upserts the same subscription, which already carries the value. */
    @Test
    fun reRegisteringTheSameSubscription_doesNotReapplyExternalId() {
        postReturnsId = OLD_SUBSCRIPTION_ID
        AppPushService.storage.remove("last_registration_hash")
        requests.clear()

        PushSubscriptionService.register(context, "test")

        assertEquals("expected the POST alone, got $requests", 1, requests.size)
        assertTrue("no new subscription, so no re-apply", patches().isEmpty())
    }

    private fun patches() = requests.filter { it.first == "PATCH" }

    /** The hash register() compares against — same recipe as PushSubscriptionService. */
    private fun currentFingerprint(): String {
        val stable = PushDeviceInfo.registrationPayload(context)
            .toSortedMap()
            .entries
            .joinToString("&") { "${it.key}=${it.value}" }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
