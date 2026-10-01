package com.appsonair.apppush.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentNotificationIdsTest {

    private val prefs by lazy {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("recent_ids_test", Context.MODE_PRIVATE)
    }

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun secondSightingIsDuplicate() {
        assertTrue(RecentNotificationIds.markIfNew(prefs, "n-1"))
        assertFalse(RecentNotificationIds.markIfNew(prefs, "n-1"))
        assertTrue(RecentNotificationIds.markIfNew(prefs, "n-2"))
    }

    @Test
    fun oldestEvictedPastCapacity() {
        repeat(RecentNotificationIds.CAPACITY) { RecentNotificationIds.markIfNew(prefs, "n-$it") }
        // One more pushes "n-0" out; "n-1" is still remembered.
        assertTrue(RecentNotificationIds.markIfNew(prefs, "n-new"))

        assertFalse(RecentNotificationIds.markIfNew(prefs, "n-1"))
        assertTrue(RecentNotificationIds.markIfNew(prefs, "n-0"))
    }

    @Test
    fun corruptStoreIsReset() {
        prefs.edit().putString("recent_notification_ids", "{not an array").commit()
        assertTrue(RecentNotificationIds.markIfNew(prefs, "n-1"))
        assertFalse(RecentNotificationIds.markIfNew(prefs, "n-1"))
    }
}
