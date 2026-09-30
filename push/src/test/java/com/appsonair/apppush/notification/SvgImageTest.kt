package com.appsonair.apppush.notification

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * BitmapFactory can't decode SVG, so an SVG big_picture / large_icon URL used to be dropped
 * with "not a valid image?". decodeScaled now falls back to AndroidSVG for SVG bytes.
 * Native graphics mode so pixels are actually drawn and can be checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SvgImageTest {

    private fun decode(svg: String, maxPx: Int) =
        PushNotificationHelper.decodeScaled(svg.toByteArray(), maxPx, "test.svg")

    @Test
    fun svgWithViewBox_rendersAtCapKeepingAspect() {
        val bitmap = decode(
            """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 24">""" +
                """<rect width="48" height="24" fill="#FF0000"/></svg>""",
            maxPx = 256
        )
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(128, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(128, 64))
    }

    @Test
    fun svgWithSizeButNoViewBox_scalesContentToFill() {
        val bitmap = decode(
            """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24">""" +
                """<rect x="12" width="12" height="24" fill="#0000FF"/></svg>""",
            maxPx = 256
        )
        assertNotNull(bitmap)
        assertEquals(256, bitmap!!.width)
        assertEquals(256, bitmap.height)
        // Right half is blue only if the content scaled with the bitmap, not drawn at 24px.
        assertEquals(Color.BLUE, bitmap.getPixel(200, 128))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(50, 128))
    }

    @Test
    fun nonImageBytes_returnNull() {
        assertNull(decode("<html><body>404 Not Found</body></html>", maxPx = 256))
    }
}
