package com.appsonair.apppush

import com.appsonair.apppush.services.PushSubscriptionService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object PushUser {

    /** The AppsOnAir-assigned device ID. */
    @JvmStatic
    val appsOnAirId: String get() = AppPushService.getDeviceId()

    /** The external user ID linked via AppPushService.login(). */
    @JvmStatic
    val externalId: String? get() = AppPushService.externalId

    @JvmStatic
    val pushSubscription: PushSubscription get() = PushSubscription

    object PushSubscription {

        @JvmStatic
        val id: String? get() = AppPushService.subscriptionId

        @JvmStatic
        val optedIn: Boolean get() = !AppPushService.isOptedOut

        @JvmStatic
        val token: String? get() = AppPushService.storage.getFcmToken()

        @JvmStatic
        fun optIn() {
            val previous = PushSubscriptionState(token = token, optedIn = !AppPushService.isOptedOut)
            AppPushService.isOptedOut = false
            AppPushService.storage.putBoolean("is_opted_out", false)
            AppPushService.log("Push subscription opted in.")
            notifyObservers(previous)
            PushSubscriptionService.optIn()
        }

        @JvmStatic
        fun optOut() {
            val previous = PushSubscriptionState(token = token, optedIn = !AppPushService.isOptedOut)
            AppPushService.isOptedOut = true
            AppPushService.storage.putBoolean("is_opted_out", true)
            AppPushService.log("Push subscription opted out.")
            notifyObservers(previous)
            PushSubscriptionService.optOut()
        }

        @JvmStatic
        fun getOptedIn(callback: (Boolean) -> Unit) {
            if (AppPushService.subscriptionId.isNullOrBlank()) {
                callback(optedIn)
                return
            }
            PushSubscriptionService.fetchOptedIn(callback)
        }

        @JvmStatic
        fun addObserver(observer: IPushSubscriptionObserver) {
            AppPushService.pushSubscriptionObservers.add(observer)
        }

        @JvmStatic
        fun removeObserver(observer: IPushSubscriptionObserver) {
            AppPushService.pushSubscriptionObservers.remove(observer)
        }

        internal fun replaceOptedIn(newOptedIn: Boolean) {
            if (newOptedIn == optedIn) return
            val previous = PushSubscriptionState(token = token, optedIn = optedIn)
            AppPushService.isOptedOut = !newOptedIn
            AppPushService.storage.putBoolean("is_opted_out", !newOptedIn)
            notifyObservers(previous)
        }

        private fun notifyObservers(previous: PushSubscriptionState) {
            val current = PushSubscriptionState(token = token, optedIn = optedIn)
            val state = PushSubscriptionChangedState(previous = previous, current = current)
            AppPushService.pushSubscriptionObservers.forEach { it.onPushSubscriptionDidChange(state) }
        }
    }

    @JvmStatic
    fun addTag(key: String, value: String) {
        AppPushService.tags[key] = value
        persistTags()
        AppPushService.log("Tag added: $key=$value")
        PushSubscriptionService.addTag(key, value)
    }

    @JvmStatic
    fun addTags(tags: Map<String, String>) {
        AppPushService.tags.putAll(tags)
        persistTags()
        AppPushService.log("Tags added: ${tags.keys.joinToString()}")
        PushSubscriptionService.addTags(tags)
    }

    @JvmStatic
    fun removeTag(key: String) {
        AppPushService.tags.remove(key)
        persistTags()
        AppPushService.log("Tag removed: $key")
        PushSubscriptionService.removeTag(key)
    }

    @JvmStatic
    fun removeTags(keys: List<String>) {
        keys.forEach { AppPushService.tags.remove(it) }
        persistTags()
        AppPushService.log("Tags removed: ${keys.joinToString()}")
        PushSubscriptionService.removeTags(keys)
    }

    
    @JvmStatic
    fun getTags(callback: (Map<String, String>) -> Unit) {
        if (AppPushService.subscriptionId.isNullOrBlank()) {
            callback(HashMap(AppPushService.tags))
            return
        }
        PushSubscriptionService.fetchTags(callback)
    }

    internal fun replaceTags(newTags: Map<String, String>) {
        AppPushService.tags.clear()
        AppPushService.tags.putAll(newTags)
        persistTags()
    }

    @JvmStatic
    fun setLanguage(languageCode: String) {
        AppPushService.language = languageCode
        AppPushService.storage.putString("language", languageCode)
        AppPushService.log("Language set to: $languageCode")
        PushSubscriptionService.updateLanguage(languageCode)
    }

    @JvmStatic
    val language: String get() = AppPushService.language

    @JvmStatic
    fun addAlias(label: String, id: String) {
        AppPushService.aliases[label] = id
        persistAliases()
        AppPushService.log("Alias added: $label=$id")
    }

    @JvmStatic
    fun addAliases(aliases: Map<String, String>) {
        AppPushService.aliases.putAll(aliases)
        persistAliases()
    }

    @JvmStatic
    fun removeAlias(label: String) {
        AppPushService.aliases.remove(label)
        persistAliases()
    }

    @JvmStatic
    fun removeAliases(labels: List<String>) {
        labels.forEach { AppPushService.aliases.remove(it) }
        persistAliases()
    }

    @JvmStatic
    fun addEmail(address: String) {
        if (address.isBlank() || AppPushService.emails.contains(address)) return
        AppPushService.emails.add(address)
        persistEmails()
        AppPushService.log("Email added: $address")
    }

    @JvmStatic
    fun removeEmail(address: String) {
        AppPushService.emails.remove(address)
        persistEmails()
    }

    // MARK: - SMS (AOA:Future — not covered in push SDK scope, will be added in a future release)

    // @JvmStatic
    // fun addSms(number: String) {
    //     if (number.isBlank() || AppPushService.smsNumbers.contains(number)) return
    //     AppPushService.smsNumbers.add(number)
    //     persistSmsNumbers()
    //     AppPushService.log("SMS number added: $number")
    // }

    // @JvmStatic
    // fun removeSms(number: String) {
    //     AppPushService.smsNumbers.remove(number)
    //     persistSmsNumbers()
    // }

    // MARK: - User State Observer

    @JvmStatic
    fun addObserver(observer: IUserStateObserver) {
        AppPushService.userStateObservers.add(observer)
    }

    @JvmStatic
    fun removeObserver(observer: IUserStateObserver) {
        AppPushService.userStateObservers.remove(observer)
    }

    private fun persistTags() {
        val json = JSONObject().apply { AppPushService.tags.forEach { put(it.key, it.value) } }
        AppPushService.storage.putString("tags_json", json.toString())
    }

    private fun persistAliases() {
        val json = JSONObject().apply { AppPushService.aliases.forEach { put(it.key, it.value) } }
        AppPushService.storage.putString("aliases_json", json.toString())
    }

    private fun persistEmails() {
        val json = JSONArray().apply { AppPushService.emails.forEach { put(it) } }
        AppPushService.storage.putString("emails_json", json.toString())
    }

    // AOA:Future — SMS persistence (uncomment when SMS support is added)
    // private fun persistSmsNumbers() {
    //     val json = JSONArray().apply { AppPushService.smsNumbers.forEach { put(it) } }
    //     AppPushService.storage.putString("sms_json", json.toString())
    // }
}
