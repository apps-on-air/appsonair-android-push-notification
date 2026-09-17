package com.appsonair.apppush

import android.app.NotificationManager
import android.app.usage.StorageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import com.appsonair.apppush.service.PushFirebaseMessagingService
import com.appsonair.apppush.services.PushApiService
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.util.UUID

/**
 * A push carrying BOTH a notification block and an "actions" data key.
 *
 * firebase-messaging draws notification-block messages itself when the app is backgrounded and
 * never calls onMessageReceived, and its renderer has no concept of action buttons — so the
 * buttons vanished on every background delivery. PushFirebaseMessagingService.handleIntent()
 * intercepts exactly those messages; reverting that override makes
 * [notificationBlockWithActions_rendersButtons] fail.
 *
 * Scope: handleIntent() is driven directly, which is the background path. The foreground path
 * (PushSessionManager.isForeground) posts to the main looper and is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationBlockActionsTest {

    private companion object {
        val VOLUME_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a3")

        const val LAUNCHER_CLASS = "com.appsonair.apppush.LauncherStub"

        const val ACTIONS_JSON =
            """[{"id":"track","title":"Track","url":"acme://orders/12345","foreground":true},""" +
                """{"id":"cancel","title":"Cancel","destructive":true,"foreground":false}]"""
    }

    private lateinit var context: Context
    private lateinit var service: PushFirebaseMessagingService


    private val manager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

        // The registration POST the FCM token triggers is not under test — swallow it.
        PushApiService.transport = { _, _, _, onResult ->
            onResult(PushApiService.Result.Success(JSONObject()))
        }

        AppPushService.initialize(context)
        shadowOf(Looper.getMainLooper()).idle()

        // PushNotificationHelper builds every intent — the content intent and each action
        // button — from getLaunchIntentForPackage(), and addActions() returns early when that
        // is null. A library's manifest declares no activity, so give the test app a launcher.
        val launcher = ComponentName(context.packageName, "$LAUNCHER_CLASS")
        shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )

        service = Robolectric.setupService(PushFirebaseMessagingService::class.java)
        manager.cancelAll()
    }

    @After
    fun tearDown() {
        PushApiService.transport = null
        manager.cancelAll()
    }

    /**
     * The wire shape FCM hands the service: the notification block as "gcm.n.*" extras, the
     * data keys as plain extras beside them. [messageId] varies per test because
     * PushFirebaseMessagingService dedups on it in a process-wide LruCache.
     */
    private fun fcmIntent(
        messageId: String,
        withNotificationBlock: Boolean = true,
        withActions: Boolean = true
    ) = Intent("com.google.android.c2dm.intent.RECEIVE").putExtras(
        Bundle().apply {
            putString("google.message_id", messageId)
            if (withNotificationBlock) {
                // The flag FCM sets to mark a notification message. Without it
                // RemoteMessage.getNotification() returns null and Firebase's own
                // handleIntent() routes the message to onMessageReceived as data-only —
                // which would make the intercept below look like it worked when it had not.
                putString("gcm.n.e", "1")
                putString("gcm.n.title", "Order shipped")
                putString("gcm.n.body", "Your order #12345 is on its way.")
                putString("gcm.n.android_channel_id", "order-updates")
            }
            putString("notification_id", messageId)
            putString("url", "https://acme.com/orders/12345")
            if (withActions) putString("actions", ACTIONS_JSON)
        }
    )

    private fun postedNotification(): android.app.Notification? {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(manager).allNotifications.singleOrNull()
    }

    // MARK: - The reported bug

    @Test
    fun notificationBlockWithActions_rendersButtons() {
        service.handleIntent(fcmIntent("msg-1"))

        val posted = postedNotification()
        assertNotNull("expected the SDK to post a notification", posted)
        assertEquals(2, posted!!.actions.size)
        assertEquals("Track", posted.actions[0].title)
        assertEquals("Cancel", posted.actions[1].title)
    }

    /** Nothing is stripped from the intent, so the notification block still supplies these. */
    @Test
    fun notificationBlockWithActions_keepsTitleBodyAndChannel() {
        service.handleIntent(fcmIntent("msg-2"))

        val posted = postedNotification()!!
        assertEquals("Order shipped", posted.extras.getString("android.title"))
        assertEquals("Your order #12345 is on its way.", posted.extras.getString("android.text"))
        // android.notification.channel_id lives only on RemoteMessage.Notification, never in
        // the data map — without that fallback this lands on the SDK's default channel.
        assertEquals("order-updates", posted.channelId)
    }

    @Test
    fun actionButton_carriesItsActionId() {
        service.handleIntent(fcmIntent("msg-3"))

        val posted = postedNotification()!!
        val intent = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals("track", intent.getStringExtra("action_id"))
        // The content intent must not carry one — that is what distinguishes a body tap.
        assertNull(shadowOf(posted.contentIntent).savedIntent.getStringExtra("action_id"))
    }

    // MARK: - Everything else keeps Firebase's path

    /**
     * The interception decision, tested directly. The branch it drives cannot be observed
     * end-to-end here: Firebase only renders a notification block itself when the app is
     * backgrounded, and under Robolectric the process always looks foregrounded, so
     * super.handleIntent() forwards to onMessageReceived either way. These pin the predicate;
     * the background behaviour needs a device.
     */
    @Test
    fun intercepts_onlyNotificationBlocksThatDeclareActions() {
        assertTrue(
            "a notification block with buttons is the case Firebase renders wrongly",
            service.shouldIntercept(fcmIntent("msg-4").extras!!)
        )
        assertFalse(
            "without buttons there is nothing Firebase gets wrong — leave it alone",
            service.shouldIntercept(fcmIntent("msg-5", withActions = false).extras!!)
        )
        assertFalse(
            "a data-only push already reaches onMessageReceived on its own",
            service.shouldIntercept(fcmIntent("msg-6", withNotificationBlock = false).extras!!)
        )
    }

    @Test
    fun dataOnlyWithActions_stillRendersButtons() {
        service.handleIntent(fcmIntent("msg-7", withNotificationBlock = false))

        // super.handleIntent() routes a data-only message to onMessageReceived itself, so this
        // pins that the intercept did not break the path that already worked.
        val posted = postedNotification()
        assertNotNull("expected the SDK to post a notification", posted)
        assertEquals(2, posted!!.actions.size)
    }
}
