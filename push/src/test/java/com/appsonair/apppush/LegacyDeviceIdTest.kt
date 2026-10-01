package com.appsonair.apppush

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import com.appsonair.core.services.CoreService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.util.UUID

/**
 * A device migrated from another push provider registers with that provider's device id as
 * device_id, so the backend matches it to the imported record instead of adding a second device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyDeviceIdTest {

    private companion object {
        val VOLUME_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    }

    private lateinit var context: Context
    private lateinit var storage: PushStorage

    private val legacyPrefs
        get() = context.getSharedPreferences(LegacyDeviceId.PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // CoreService.getDeviceId() builds Core's DeviceInfoService, which reads storage
        // figures that Robolectric only answers once given data.
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

        storage = PushStorage(context)
        storage.prefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        storage.prefs.edit().clear().commit()
        legacyPrefs.edit().clear().commit()
    }

    private fun subscriptions(vararg models: JSONObject) = JSONArray(models.toList()).toString()

    private fun model(id: String, type: String = "PUSH") = JSONObject().put("id", id).put("type", type)

    // MARK: - Reading the legacy prefs

    @Test
    fun currentStore_pushSubscriptionId() {
        val id = LegacyDeviceId.from(
            mapOf(
                "MODEL_STORE_subscriptions" to subscriptions(
                    model("email-sub", type = "EMAIL"),
                    model("7e4065a1-0000-4000-8000-000000000001")
                )
            )
        )
        assertEquals("7e4065a1-0000-4000-8000-000000000001", id)
    }

    @Test
    fun currentStorePreferredOverOlderKey() {
        val id = LegacyDeviceId.from(
            mapOf("GT_PLAYER_ID" to "older-id", "MODEL_STORE_subscriptions" to subscriptions(model("current-id")))
        )
        assertEquals("current-id", id)
    }

    @Test
    fun olderKey() {
        assertEquals("older-id", LegacyDeviceId.from(mapOf("GT_PLAYER_ID" to "older-id")))
    }

    @Test
    fun unsyncedLocalId_fallsBackToOlderKey_orNothing() {
        val local = subscriptions(model("local-abc"))
        assertEquals(
            "older-id",
            LegacyDeviceId.from(mapOf("GT_PLAYER_ID" to "older-id", "MODEL_STORE_subscriptions" to local))
        )
        assertNull(LegacyDeviceId.from(mapOf("MODEL_STORE_subscriptions" to local)))
    }

    @Test
    fun malformedStore_fallsBackToOlderKey() {
        val id = LegacyDeviceId.from(
            mapOf("GT_PLAYER_ID" to "older-id", "MODEL_STORE_subscriptions" to "{not json")
        )
        assertEquals("older-id", id)
    }

    @Test
    fun nothingPresent() {
        assertNull(LegacyDeviceId.from(emptyMap<String, Any>()))
    }

    // MARK: - Which id the device uses

    @Test
    fun migratedInstall_usesLegacyId() {
        legacyPrefs.edit()
            .putString("MODEL_STORE_subscriptions", subscriptions(model("current-id")))
            .commit()

        assertEquals("current-id", storage.deviceId)
    }

    @Test
    fun noLegacyData_usesCoreDeviceId() {
        assertEquals(CoreService.getDeviceId(context), storage.deviceId)
    }

    /** Persisted on first read: legacy data appearing or vanishing later changes nothing. */
    @Test
    fun choiceIsStable() {
        assertEquals(CoreService.getDeviceId(context), storage.deviceId)

        legacyPrefs.edit().putString("GT_PLAYER_ID", "older-id").commit()
        assertEquals(CoreService.getDeviceId(context), storage.deviceId)
        // A fresh PushStorage (next launch) reads the persisted choice too.
        assertEquals(CoreService.getDeviceId(context), PushStorage(context).deviceId)
    }

    /**
     * Registered under Core's id by an earlier SDK version (before this choice was persisted):
     * keep it, or the backend would see a brand-new device.
     */
    @Test
    fun alreadyRegistered_keepsCoreDeviceId() {
        storage.putString("subscription_id", "sub-existing")
        legacyPrefs.edit().putString("GT_PLAYER_ID", "older-id").commit()

        assertEquals(CoreService.getDeviceId(context), storage.deviceId)
    }

    @Test
    fun registrationAlreadyAnswered_keepsCoreDeviceId() {
        storage.isRegistrationRequired = false
        legacyPrefs.edit().putString("GT_PLAYER_ID", "older-id").commit()

        assertEquals(CoreService.getDeviceId(context), storage.deviceId)
    }
}
