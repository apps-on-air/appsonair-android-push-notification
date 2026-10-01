package com.appsonair.apppush.service

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * The last [CAPACITY] `notification_id`s this device has handled, persisted so a duplicate is
 * caught even across a process restart.
 *
 * This complements the in-memory messageId dedup in [PushFirebaseMessagingService], which only
 * catches FCM redelivering the *same* message. A device migrated from another push provider
 * can briefly be reachable through two tokens — the imported one and its new one — so one
 * notification arrives as two distinct FCM messages, possibly minutes apart. Only the
 * backend's notification_id ties them together.
 */
internal object RecentNotificationIds {

    internal const val CAPACITY = 100
    private const val KEY = "recent_notification_ids"

    /**
     * Records [id] and returns true if it's new; false if it was already handled. commit(), not
     * apply(): the FCM service process can be killed right after, and a lost write is exactly
     * the duplicate this exists to stop.
     */
    @Synchronized
    fun markIfNew(prefs: SharedPreferences, id: String): Boolean {
        val ids = load(prefs)
        if (id in ids) return false
        ids += id
        while (ids.size > CAPACITY) ids.removeAt(0)
        prefs.edit().putString(KEY, JSONArray(ids).toString()).commit()
        return true
    }

    private fun load(prefs: SharedPreferences): MutableList<String> = try {
        val arr = JSONArray(prefs.getString(KEY, null) ?: return mutableListOf())
        MutableList(arr.length()) { arr.getString(it) }
    } catch (_: Throwable) {
        // Corrupt entry: start over rather than block every notification.
        mutableListOf()
    }
}
