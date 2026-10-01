package com.appsonair.apppush

import android.content.Context
import org.json.JSONArray

/**
 * Reads the device id a previously integrated push provider left on disk, for apps migrating
 * to this SDK.
 *
 * The backend imports that provider's devices keyed by this id, so registering with it as
 * `device_id` makes the device land on its imported record instead of becoming a second
 * device. See [PushStorage.deviceId], which decides when it's used.
 *
 * Best-effort: any read or parse failure just means "not found".
 */
internal object LegacyDeviceId {

    /** The provider's SharedPreferences file — the same name across its SDK versions. */
    internal const val PREFS = "OneSignal"

    /** Current SDK versions: the subscriptions model store, a JSON array of subscription models. */
    private const val KEY_SUBSCRIPTIONS = "MODEL_STORE_subscriptions"

    /** Older SDK versions: the same id under its earlier name. */
    private const val KEY_OLDER_ID = "GT_PLAYER_ID"

    fun read(context: Context): String? {
        val entries = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        } catch (t: Throwable) {
            AppPushService.log("Legacy device id unreadable — using the AppsOnAir device id.", LogLevel.WARN, t)
            return null
        }
        return from(entries)
    }

    /**
     * The push subscription from the current store first, then the older key: an app that
     * upgraded the provider's SDK keeps the old key, but the current one was reported last.
     * Ids prefixed "local-" were never synced to the provider, so they can't be in the import.
     */
    internal fun from(entries: Map<String, *>): String? {
        val current = try {
            (entries[KEY_SUBSCRIPTIONS] as? String)?.let { json ->
                val models = JSONArray(json)
                (0 until models.length())
                    .mapNotNull { models.optJSONObject(it) }
                    .filter { it.optString("type").equals("PUSH", ignoreCase = true) }
                    .map { it.optString("id") }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("local-") }
            }
        } catch (t: Throwable) {
            AppPushService.log("Unreadable legacy subscription store.", LogLevel.WARN, t)
            null
        }
        return current ?: (entries[KEY_OLDER_ID] as? String)?.takeIf { it.isNotBlank() }
    }
}
