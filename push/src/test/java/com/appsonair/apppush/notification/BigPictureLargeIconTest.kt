package com.appsonair.apppush.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import androidx.test.core.app.ApplicationProvider
import com.appsonair.apppush.PushNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * BigPictureStyle used to call bigLargeIcon(null) unconditionally. That only exists so the
 * legacy image_url, which fills both slots, doesn't show twice when expanded — but it also hid
 * a different large_icon (an avatar or logo) as soon as the notification was expanded.
 * Making the call unconditional again fails [differentImages_keepLargeIconWhenExpanded].
 *
 * Images are served from a local HTTP server; native graphics mode so they decode to real pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BigPictureLargeIconTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var server: ServerSocket

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(true)

        server = serveImages(mapOf("/red.png" to pngOf(Color.RED), "/blue.png" to pngOf(Color.BLUE)))
    }

    @After
    fun tearDown() {
        server.close()
    }

    /**
     * Minimal HTTP/1.0 server on loopback, one request per connection. The JDK's HttpServer
     * isn't on the Android unit-test classpath.
     */
    private fun serveImages(images: Map<String, ByteArray>): ServerSocket {
        val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1)
                    // Drain the headers — closing with unread input resets the connection.
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val body = images[path]
                    val out = it.getOutputStream()
                    if (body == null) {
                        out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    } else {
                        out.write("HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }
        return socket
    }

    private fun pngOf(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"

    /**
     * Shows from a worker thread: on the main thread show() hands image downloads to its own
     * thread and returns before posting, while off it everything runs inline.
     */
    private fun show(data: Map<String, String>, imageUrl: String? = null): Notification {
        val push = PushNotification(
            id = "n-1", title = "Order", body = "Shipped",
            data = data + ("notification_id" to "n-1"), imageUrl = imageUrl, sound = null
        )
        thread { PushNotificationHelper.show(context, push) }.join()
        return manager.activeNotifications.single().notification
    }

    private fun Notification.bigPicture(): Bitmap? =
        extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)

    private fun Notification.largeIconColor(): Int {
        val bitmap = (getLargeIcon().loadDrawable(context) as BitmapDrawable).bitmap
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
    }

    /** BigPictureStyle stores bigLargeIcon(null) as this key mapped to null; absent means "keep it". */
    private fun Notification.hidesLargeIconWhenExpanded(): Boolean =
        extras.containsKey(Notification.EXTRA_LARGE_ICON_BIG) &&
            extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG, Icon::class.java) == null

    @Test
    fun differentImages_keepLargeIconWhenExpanded() {
        val n = show(mapOf("big_picture" to url("/red.png"), "large_icon" to url("/blue.png")))

        val picture = n.bigPicture()
        assertNotNull("big picture should have loaded", picture)
        assertEquals(Color.RED, picture!!.getPixel(picture.width / 2, picture.height / 2))
        assertEquals(Color.BLUE, n.largeIconColor())
        assertFalse("a different large_icon must stay visible when expanded", n.hidesLargeIconWhenExpanded())
    }

    @Test
    fun sameUrlInBothKeys_hidesLargeIconWhenExpanded() {
        val n = show(mapOf("big_picture" to url("/red.png"), "large_icon" to url("/red.png")))

        assertNotNull(n.bigPicture())
        assertEquals(Color.RED, n.largeIconColor())
        assertTrue("the same image must not show twice when expanded", n.hidesLargeIconWhenExpanded())
    }

    @Test
    fun legacyImageUrl_hidesLargeIconWhenExpanded() {
        val n = show(emptyMap(), imageUrl = url("/red.png"))

        assertNotNull(n.bigPicture())
        assertEquals(Color.RED, n.largeIconColor())
        assertTrue(n.hidesLargeIconWhenExpanded())
    }

    @Test
    fun bigPictureOnly_hasNoLargeIcon() {
        val n = show(mapOf("big_picture" to url("/red.png")))

        assertNotNull(n.bigPicture())
        assertNull(n.getLargeIcon())
    }
}
