package com.appsonair.apppush.services

import android.content.Context
import com.appsonair.apppush.PushDeviceInfo
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.PushUser
import com.appsonair.apppush.LogLevel
import com.appsonair.apppush.utils.StringConst
import org.json.JSONArray
import org.json.JSONObject


internal object PushSubscriptionService {

    private const val KEY_SUBSCRIPTION_ID = "subscription_id"

    @Volatile private var inFlight = false

    fun register(context: Context, reason: String) {
        val storage = AppPushService.storage

        if (storage.getFcmToken().isNullOrBlank()) {
            AppPushService.log("Registration skipped ($reason) — no FCM token yet.", LogLevel.DEBUG)
            return
        }

        synchronized(this) {
            if (inFlight) {
                AppPushService.log("Registration skipped ($reason) — one already in flight.", LogLevel.DEBUG)
                return
            }
            inFlight = true
        }

        // Close out a session left dangling by a kill/crash (never made it through
        // handleBackground()'s PATCH) before this device re-registers — otherwise it would sit
        // open on the backend indefinitely while the SDK moves on to a new one. This is a
        // PATCH-only close, never a POST, so it can never itself start a new session — register
        // proceeds afterward regardless of whether the close succeeded, failed, or was offline.
        PushSessionService.endStaleSessionIfNeeded {
            val payload = PushDeviceInfo.registrationPayload(context)
            AppPushService.log("Registering device ($reason)...", LogLevel.INFO)

            PushApiService.post(StringConst.Subscriptions, JSONObject(payload)) { result ->
                inFlight = false
                when (result) {
                    is PushApiService.Result.Success -> {
                        val id = result.body
                            .optString(StringConst.SubscriptionIdKey)
                            .takeIf { it.isNotBlank() }

                    val previousId = AppPushService.subscriptionId
                    if (id != null) AppPushService.subscriptionId = id

                        val sessionId = result.body.optString(StringConst.SessionIdResponseKey)
                            .takeIf { it.isNotBlank() }
                        if (sessionId != null) {
                            AppPushService.log(
                                "Registration ($reason): response carried sessionId=$sessionId — storing.",
                                LogLevel.INFO
                            )
                            PushSessionService.adoptFromRegistration(sessionId)
                        } else {
                            AppPushService.log(
                                "Registration ($reason): response had no sessionId — not stored.",
                                LogLevel.WARN
                            )
                        }

                    AppPushService.log(
                        "Device registered ($reason). subscriptionId=${id ?: "(none returned)"}",
                        LogLevel.INFO
                    )

                    // external_id is not part of the registration payload, and login() can only
                    // PATCH it onto a subscription that already exists — so a login() made
                    // before this point never reached the backend and nothing retried it. That
                    // is reachable at startup (login() before the first registration) and in
                    // the logout -> re-register window, where an account switch would otherwise
                    // leave the device anonymous server-side until the next explicit login().
                    //
                    // Gated on the id having changed: an unchanged re-registration upserts the
                    // same subscription, which already carries the value.
                    val externalId = AppPushService.externalId
                    if (id != null && id != previousId && !externalId.isNullOrBlank()) {
                        AppPushService.log(
                            "Re-applying external id to subscription $id.",
                            LogLevel.INFO
                        )
                        updateExternalId(externalId)
                    }
                }

                    is PushApiService.Result.Failure -> {
                        AppPushService.log(
                            "Registration failed ($reason) — ${result.message}. " +
                                "Will retry on next app open.",
                            LogLevel.ERROR
                        )
                    }
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

    /**
     * Deletes the current subscription, then registers a fresh one, from [AppPushService.logout].
     *
     * The external id lives on the subscription row, so dropping the row is what detaches the
     * user; the registration that follows brings the device back as an anonymous subscriber
     * with a new id. A failed DELETE leaves the existing subscription untouched — re-registering
     * then would leave two live rows for one device.
     */
    fun deleteAndReregister(context: Context, reason: String) {
        val storage = AppPushService.storage
        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log(
                "Subscription delete skipped ($reason) — no subscription id yet.",
                LogLevel.DEBUG
            )
            return
        }

        AppPushService.log("Deleting subscription ($reason)...", LogLevel.INFO)

        PushApiService.delete("${StringConst.Subscriptions}/$subscriptionId") { result ->
            when (result) {
                is PushApiService.Result.Success -> {
                    AppPushService.subscriptionId = null
                    storage.prefs.edit()
                        .remove(KEY_SUBSCRIPTION_ID)
                        .commit()

                    AppPushService.log(
                        "Subscription deleted ($reason). Registering a new one...",
                        LogLevel.INFO
                    )
                    register(context, reason)
                }

                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Subscription delete failed ($reason) — ${result.message}. " +
                            "The existing subscription is unchanged.",
                        LogLevel.ERROR
                    )
            }
        }
    }

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

                is PushApiService.Result.Failure ->
                    AppPushService.log(
                        "Update failed ($label) — ${result.message}. " +
                            "The next registration will carry the current value.",
                        LogLevel.ERROR
                    )
            }
        }
    }
}
