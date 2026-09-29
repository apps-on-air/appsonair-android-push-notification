package com.appsonair.apppush.notification

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.appsonair.apppush.PushNotification
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NotificationManager identifies a notification by (tag, id). collapse_key was passed as the
 * tag but the id came from each push's own notification_id, so two pushes sharing a key never
 * matched and stacked in the tray. Reverting the fixed id in PushNotificationHelper makes
 * [sameCollapseKey_replacesEarlierNotification] fail with two notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CollapseKeyTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(true)
    }

    /** No image keys, so show() needs no network and posts inline on the test thread. */
    private fun show(id: String, body: String, collapseKey: String? = null) {
        val data = buildMap {
            put("notification_id", id)
            collapseKey?.let { put("collapse_key", it) }
        }
        PushNotificationHelper.show(
            context,
            PushNotification(id = id, title = "Order", body = body, data = data, imageUrl = null, sound = null)
        )
    }

    private fun bodyOf(n: android.service.notification.StatusBarNotification): String? =
        n.notification.extras.getCharSequence("android.text")?.toString()

    @Test
    fun sameCollapseKey_replacesEarlierNotification() {
        show(id = "n-1", body = "Packed", collapseKey = "order-42")
        show(id = "n-2", body = "Shipped", collapseKey = "order-42")

        val active = manager.activeNotifications
        assertEquals("expected one notification, got ${active.size}", 1, active.size)
        assertEquals("order-42", active.single().tag)
        assertEquals("Shipped", bodyOf(active.single()))
    }

    @Test
    fun differentCollapseKeys_stack() {
        show(id = "n-1", body = "Order 42", collapseKey = "order-42")
        show(id = "n-2", body = "Order 43", collapseKey = "order-43")

        assertEquals(2, manager.activeNotifications.size)
    }

    @Test
    fun noCollapseKey_stacks() {
        show(id = "n-1", body = "First")
        show(id = "n-2", body = "Second")

        assertEquals(2, manager.activeNotifications.size)
    }

    @Test
    fun blankCollapseKey_isIgnored() {
        // A blank key would otherwise become a shared tag and collapse unrelated pushes.
        show(id = "n-1", body = "First", collapseKey = "")
        show(id = "n-2", body = "Second", collapseKey = "")

        assertEquals(2, manager.activeNotifications.size)
    }
}
