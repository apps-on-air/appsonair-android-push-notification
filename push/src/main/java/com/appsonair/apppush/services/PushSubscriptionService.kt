package com.appsonair.apppush.services

import android.content.Context
import com.appsonair.apppush.PushDeviceInfo
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.PushUser
import com.appsonair.apppush.LogLevel
import com.appsonair.apppush.utils.StringConst
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest


internal object PushSubscriptionService {

    private const val KEY_LAST_HASH      = "last_registration_hash"
    private const val KEY_PENDING        = "registration_pending"
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"

    @Volatile private var inFlight = false

    fun register(context: Context, reason: String) {
        val storage = AppPushService.storage

        if (storage.getFcmToken().isNullOrBlank()) {
            AppPushService.log("Registration skipped ($reason) — no FCM token yet.", LogLevel.DEBUG)
            return
        }

        val payload = PushDeviceInfo.registrationPayload(context)
        val fingerprint = fingerprintOf(payload)
        val isPending = storage.getBoolean(KEY_PENDING)

        if (!isPending && fingerprint == storage.getString(KEY_LAST_HASH)) {
            AppPushService.log(
                "Registration skipped ($reason) — subscription unchanged since last POST.",
                LogLevel.DEBUG
            )
            return
        }

        synchronized(this) {
            if (inFlight) {
                AppPushService.log("Registration skipped ($reason) — one already in flight.", LogLevel.DEBUG)
                return
            }
            inFlight = true
        }

        AppPushService.log("Registering device ($reason)...", LogLevel.INFO)

        PushApiService.post(StringConst.Subscriptions, JSONObject(payload)) { result ->
            inFlight = false
            when (result) {
                is PushApiService.Result.Success -> {
                    val id = result.body
                        .optString(StringConst.SubscriptionIdKey)
                        .takeIf { it.isNotBlank() }

                    if (id != null) AppPushService.subscriptionId = id

                    // commit(), not apply(): this runs on OkHttp's dispatcher thread and the
                    // process can be killed immediately after — the same reasoning that made
                    // setBadgeCount() use commit().
                    storage.prefs.edit()
                        .apply { if (id != null) putString(KEY_SUBSCRIPTION_ID, id) }
                        .putString(KEY_LAST_HASH, fingerprint)
                        .putBoolean(KEY_PENDING, false)
                        .commit()

                    AppPushService.log(
                        "Device registered ($reason). subscriptionId=${id ?: "(none returned)"}",
                        LogLevel.INFO
                    )
                }

                is PushApiService.Result.Failure -> {
                    storage.prefs.edit()
                        .putBoolean(KEY_PENDING, result.retryable)
                        .apply { if (!result.retryable) putString(KEY_LAST_HASH, fingerprint) }
                        .commit()

                    AppPushService.log(
                        "Registration failed ($reason) — ${result.message}. " +
                            if (result.retryable) "Will retry on next session start."
                            else "Not retrying.",
                        LogLevel.ERROR
                    )
                }
            }
        }
    }

    fun fetchTags(onResult: (Map<String, String>) -> Unit) {
        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log("Fetch tags skipped — no subscription id yet.", LogLevel.DEBUG)
            postToMain(HashMap(AppPushService.tags), onResult)
            return
        }

        PushApiService.get("${StringConst.Subscriptions}/$subscriptionId/${StringConst.Tags}") { result ->
            when (result) {
                is PushApiService.Result.Success -> {
                    val tagsObj = result.body.optJSONObject(StringConst.TagsKey) ?: result.body
                    val tags = mutableMapOf<String, String>()
                    tagsObj.keys().forEach { key -> tags[key] = tagsObj.optString(key) }

                    PushUser.replaceTags(tags)
                    AppPushService.log("Tags fetched (${tags.size}).", LogLevel.INFO)
                    postToMain(tags, onResult)
                }

                is PushApiService.Result.Failure -> {
                    AppPushService.log(
                        "Fetch tags failed — ${result.message}. Returning local cache.",
                        LogLevel.ERROR
                    )
                    postToMain(HashMap(AppPushService.tags), onResult)
                }
            }
        }
    }

    private fun <T> postToMain(value: T, onResult: (T) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(value) }
    }

    fun addTag(key: String, value: String) = addTags(mapOf(key to value))

    fun addTags(tags: Map<String, String>) {
        if (tags.isEmpty()) return

        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log(
                "Add tag(s) skipped (${tags.keys.joinToString()}) — no subscription id yet.",
                LogLevel.DEBUG
            )
            return
        }

        val body = JSONArray(
            tags.map { (key, value) ->
                JSONObject().put(StringConst.TagKey, key).put(StringConst.TagValueKey, value)
            }
        )
        val path = "${StringConst.Subscriptions}/$subscriptionId/${StringConst.Tags}"

        PushApiService.post(path, body) { result ->
            when (result) {
                is PushApiService.Result.Success ->
                    AppPushService.log("Tag(s) added (${tags.keys.joinToString()}).", LogLevel.INFO)

                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Add tag(s) failed (${tags.keys.joinToString()}) — ${result.message}.",
                        LogLevel.ERROR
                    )
            }
        }
    }

    /** POST one tag key to remove (`POST subscriptions/<id>/tags/remove`, one-element array body), from [PushUser.removeTag]. */
    fun removeTag(key: String) = removeTags(listOf(key))

    fun removeTags(keys: List<String>) {
        if (keys.isEmpty()) return

        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log(
                "Remove tags skipped (${keys.joinToString()}) — no subscription id yet.",
                LogLevel.DEBUG
            )
            return
        }

        val body = JSONObject().put(StringConst.TagKeysKey, JSONArray(keys))
        val path = "${StringConst.Subscriptions}/$subscriptionId/${StringConst.Tags}/${StringConst.TagsRemove}"

        PushApiService.post(path, body) { result ->
            when (result) {
                is PushApiService.Result.Success ->
                    AppPushService.log("Tags removed (${keys.joinToString()}).", LogLevel.INFO)

                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Remove tags failed (${keys.joinToString()}) — ${result.message}.",
                        LogLevel.ERROR
                    )
            }
        }
    }


    fun optIn() = postOptState(StringConst.OptIn, optedIn = true)

    fun optOut() = postOptState(StringConst.OptOut, optedIn = false)

    private fun postOptState(segment: String, optedIn: Boolean) {
        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log("Opt-${if (optedIn) "in" else "out"} skipped — no subscription id yet.", LogLevel.DEBUG)
            return
        }

        val path = "${StringConst.Subscriptions}/$subscriptionId/$segment"

        PushApiService.post(path, JSONObject()) { result ->
            when (result) {
                is PushApiService.Result.Success ->
                    AppPushService.log("Opted ${if (optedIn) "in" else "out"}.", LogLevel.INFO)

                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Opt-${if (optedIn) "in" else "out"} failed — ${result.message}.",
                        LogLevel.ERROR
                    )
            }
        }
    }

    fun fetchOptedIn(onResult: (Boolean) -> Unit) {
        val subscriptionId = AppPushService.subscriptionId
        val localOptedIn = !AppPushService.isOptedOut
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log("Fetch opted-in skipped — no subscription id yet.", LogLevel.DEBUG)
            postToMain(localOptedIn, onResult)
            return
        }

        PushApiService.get("${StringConst.Subscriptions}/$subscriptionId/${StringConst.OptedIn}") { result ->
            when (result) {
                is PushApiService.Result.Success -> {
                    val optedIn = result.body.optBoolean(StringConst.OptedInKey, localOptedIn)
                    PushUser.PushSubscription.replaceOptedIn(optedIn)
                    AppPushService.log("Opted-in fetched: $optedIn.", LogLevel.INFO)
                    postToMain(optedIn, onResult)
                }

                is PushApiService.Result.Failure -> {
                    AppPushService.log(
                        "Fetch opted-in failed — ${result.message}. Returning local cache.",
                        LogLevel.ERROR
                    )
                    postToMain(localOptedIn, onResult)
                }
            }
        }
    }

    fun updateLanguage(languageCode: String) {
        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log(
                "Update language skipped ($languageCode) — no subscription id yet. " +
                    "The next registration will carry the current value.",
                LogLevel.DEBUG
            )
            return
        }

        val body = JSONObject().put(StringConst.LanguageKey, languageCode)
        val path = "${StringConst.Subscriptions}/$subscriptionId/${StringConst.Language}"

        PushApiService.patch(path, body) { result ->
            when (result) {
                is PushApiService.Result.Success ->
                    AppPushService.log("Language updated: $languageCode.", LogLevel.INFO)

                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Update language failed ($languageCode) — ${result.message}.",
                        LogLevel.ERROR
                    )
            }
        }
    }

    fun updateEnabled(enabled: Boolean) =
        patchField(StringConst.EnabledKey, enabled, "permission (enabled=$enabled)")

    fun updateToken(token: String) =
        patchField(StringConst.PushTokenKey, token, "push token")

    fun updateExternalId(externalId: String) =
        patchField(StringConst.ExternalIdKey, externalId, "external id")

    fun clearExternalId() =
        patchField(StringConst.ExternalIdKey, JSONObject.NULL, "external id (logout)")

    private fun patchField(field: String, value: Any, label: String) {
        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log(
                "Update skipped ($label) — no subscription id yet. " +
                    "The next registration will carry the current value.",
                LogLevel.DEBUG
            )
            return
        }

        AppPushService.log("Updating $label...", LogLevel.INFO)
        val body = JSONObject().put(field, value)

        PushApiService.patch("${StringConst.Subscriptions}/$subscriptionId", body) { result ->
            when (result) {
                is PushApiService.Result.Success ->
                    AppPushService.log("Updated $label.", LogLevel.INFO)

                // No retry flag: KEY_PENDING drives register(), so setting it here would
                // make the next launch POST a full registration for a one-field change.
                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Update failed ($label) — ${result.message}. " +
                            "The next registration will carry the current value.",
                        LogLevel.ERROR
                    )
            }
        }
    }

    private fun fingerprintOf(payload: Map<String, Any>): String {
        val stable = payload.toSortedMap()
            .entries
            .joinToString("&") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
