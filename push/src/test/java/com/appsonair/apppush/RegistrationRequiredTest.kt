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
import org.junit.Assert.assertFalse
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
 * `is_registration_required` rides on every registration POST: true on a fresh installation,
 * and flipped to false — persisted — only once a registration is answered with HTTP 200.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RegistrationRequiredTest {

    private companion object {
        val VOLUME_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
        const val FLAG = "is_registration_required"
    }

    private lateinit var context: Context

    /** Every registration body the SDK POSTed, in order. */
    private val registrations = mutableListOf<JSONObject>()

    /** What the next POST answers with; set per-test. */
    private var postResult: PushApiService.Result =
        PushApiService.Result.Success(JSONObject().put("subscriptionId", "sub-1"), 200)

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

        PushApiService.transport = { method, path, body, onResult ->
            when {
                method == "POST" && path == "subscriptions" -> {
                    registrations += body as JSONObject
                    onResult(postResult)
                }
                else -> onResult(PushApiService.Result.Success(JSONObject()))
            }
        }

        AppPushService.initialize(context)
        shadowOf(Looper.getMainLooper()).idle()

        // A fresh installation: empty prefs apart from the token register() needs.
        AppPushService.storage.prefs.edit().clear().commit()
        AppPushService.storage.saveFcmToken("fcm-token-test")
        AppPushService.subscriptionId = null
        registrations.clear()
    }

    @After
    fun tearDown() {
        PushApiService.transport = null
        AppPushService.storage.prefs.edit().clear().commit()
    }

    @Test
    fun freshInstall_defaultsToTrue() {
        assertTrue(AppPushService.storage.isRegistrationRequired)
    }

    @Test
    fun registrationAnswered200_sendsTrueThenPersistsFalse() {
        PushSubscriptionService.register(context, "test")

        assertTrue(registrations.single().getBoolean(FLAG))
        assertFalse(AppPushService.storage.isRegistrationRequired)
        // Written to the prefs themselves, so the next launch reads it back.
        assertFalse(AppPushService.storage.prefs.getBoolean(FLAG, true))

        // The next launch's registration carries the persisted value.
        PushSubscriptionService.register(context, "next launch")
        assertFalse(registrations.last().getBoolean(FLAG))
    }

    @Test
    fun failedRegistration_keepsTrue() {
        postResult = PushApiService.Result.Failure("HTTP 500", true)

        PushSubscriptionService.register(context, "test")
        PushSubscriptionService.register(context, "next launch")

        assertEquals(2, registrations.size)
        assertTrue(registrations.all { it.getBoolean(FLAG) })
        assertTrue(AppPushService.storage.isRegistrationRequired)
    }

    @Test
    fun non200Success_keepsTrue() {
        postResult = PushApiService.Result.Success(JSONObject().put("subscriptionId", "sub-1"), 201)

        PushSubscriptionService.register(context, "test")

        assertTrue(AppPushService.storage.isRegistrationRequired)
    }

    /** The DELETE drops the row, so the replacement must take the backend's direct insert. */
    @Test
    fun logoutReregistration_sendsTrueThenPersistsFalse() {
        PushSubscriptionService.register(context, "test")

        PushSubscriptionService.deleteAndReregister(context, "logout")

        assertTrue(registrations.last().getBoolean(FLAG))
        assertFalse(AppPushService.storage.isRegistrationRequired)
    }

    /** Deleted row, failed replacement: the next launch must still ask for the direct insert. */
    @Test
    fun logoutWithFailedReregistration_keepsTrue() {
        PushSubscriptionService.register(context, "test")
        postResult = PushApiService.Result.Failure("HTTP 500", true)

        PushSubscriptionService.deleteAndReregister(context, "logout")

        assertTrue(AppPushService.storage.isRegistrationRequired)
    }

    /** A failed DELETE leaves the existing row in place, so nothing needs re-creating. */
    @Test
    fun logoutWithFailedDelete_keepsFalse() {
        PushSubscriptionService.register(context, "test")
        PushApiService.transport = { method, _, _, onResult ->
            if (method == "DELETE") onResult(PushApiService.Result.Failure("HTTP 500", true))
            else onResult(PushApiService.Result.Success(JSONObject()))
        }

        PushSubscriptionService.deleteAndReregister(context, "logout")

        assertFalse(AppPushService.storage.isRegistrationRequired)
    }
}
