package com.appsonair.push

import com.appsonair.push.services.AppsOnAirSubscriptionService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object AppsOnAirUser {

    /** The AppsOnAir-assigned device ID. */
    @JvmStatic
    val appsOnAirId: String get() = AppsOnAirPush.getDeviceId()

    /** The external user ID linked via AppsOnAirPush.login(). */
    @JvmStatic
    val externalId: String? get() = AppsOnAirPush.externalId

    @JvmStatic
    val pushSubscription: PushSubscription get() = PushSubscription

    object PushSubscription {

        @JvmStatic
        val id: String? get() = AppsOnAirPush.subscriptionId

        @JvmStatic
        val optedIn: Boolean get() = !AppsOnAirPush.isOptedOut

        @JvmStatic
        val token: String? get() = AppsOnAirPush.storage.getFcmToken()

        @JvmStatic
        fun optIn() {
            val previous = PushSubscriptionState(token = token, optedIn = !AppsOnAirPush.isOptedOut)
            AppsOnAirPush.isOptedOut = false
            AppsOnAirPush.storage.putBoolean("is_opted_out", false)
            AppsOnAirPush.log("Push subscription opted in.")
            notifyObservers(previous)
            AppsOnAirSubscriptionService.optIn()
        }

        @JvmStatic
        fun optOut() {
            val previous = PushSubscriptionState(token = token, optedIn = !AppsOnAirPush.isOptedOut)
            AppsOnAirPush.isOptedOut = true
            AppsOnAirPush.storage.putBoolean("is_opted_out", true)
            AppsOnAirPush.log("Push subscription opted out.")
            notifyObservers(previous)
            AppsOnAirSubscriptionService.optOut()
        }

        @JvmStatic
        fun getOptedIn(callback: (Boolean) -> Unit) {
            if (AppsOnAirPush.subscriptionId.isNullOrBlank()) {
                callback(optedIn)
                return
            }
            AppsOnAirSubscriptionService.fetchOptedIn(callback)
        }

        @JvmStatic
        fun addObserver(observer: IPushSubscriptionObserver) {
            AppsOnAirPush.pushSubscriptionObservers.add(observer)
        }

        @JvmStatic
        fun removeObserver(observer: IPushSubscriptionObserver) {
            AppsOnAirPush.pushSubscriptionObservers.remove(observer)
        }

        internal fun replaceOptedIn(newOptedIn: Boolean) {
            if (newOptedIn == optedIn) return
            val previous = PushSubscriptionState(token = token, optedIn = optedIn)
            AppsOnAirPush.isOptedOut = !newOptedIn
            AppsOnAirPush.storage.putBoolean("is_opted_out", !newOptedIn)
            notifyObservers(previous)
        }

        private fun notifyObservers(previous: PushSubscriptionState) {
            val current = PushSubscriptionState(token = token, optedIn = optedIn)
            val state = PushSubscriptionChangedState(previous = previous, current = current)
            AppsOnAirPush.pushSubscriptionObservers.forEach { it.onPushSubscriptionDidChange(state) }
        }
    }

    @JvmStatic
    fun addTag(key: String, value: String) {
        AppsOnAirPush.tags[key] = value
        persistTags()
        AppsOnAirPush.log("Tag added: $key=$value")
        AppsOnAirSubscriptionService.addTag(key, value)
    }

    @JvmStatic
    fun addTags(tags: Map<String, String>) {
        AppsOnAirPush.tags.putAll(tags)
        persistTags()
        AppsOnAirPush.log("Tags added: ${tags.keys.joinToString()}")
        AppsOnAirSubscriptionService.addTags(tags)
    }

    @JvmStatic
    fun removeTag(key: String) {
        AppsOnAirPush.tags.remove(key)
        persistTags()
        AppsOnAirPush.log("Tag removed: $key")
        AppsOnAirSubscriptionService.removeTag(key)
    }

    @JvmStatic
    fun removeTags(keys: List<String>) {
        keys.forEach { AppsOnAirPush.tags.remove(it) }
        persistTags()
        AppsOnAirPush.log("Tags removed: ${keys.joinToString()}")
        AppsOnAirSubscriptionService.removeTags(keys)
    }

    
    @JvmStatic
    fun getTags(callback: (Map<String, String>) -> Unit) {
        if (AppsOnAirPush.subscriptionId.isNullOrBlank()) {
            callback(HashMap(AppsOnAirPush.tags))
            return
        }
        AppsOnAirSubscriptionService.fetchTags(callback)
    }

    internal fun replaceTags(newTags: Map<String, String>) {
        AppsOnAirPush.tags.clear()
        AppsOnAirPush.tags.putAll(newTags)
        persistTags()
    }

    @JvmStatic
    fun setLanguage(languageCode: String) {
        AppsOnAirPush.language = languageCode
        AppsOnAirPush.storage.putString("language", languageCode)
        AppsOnAirPush.log("Language set to: $languageCode")
        AppsOnAirSubscriptionService.updateLanguage(languageCode)
    }

    @JvmStatic
    val language: String get() = AppsOnAirPush.language

    @JvmStatic
    fun addAlias(label: String, id: String) {
        AppsOnAirPush.aliases[label] = id
        persistAliases()
        AppsOnAirPush.log("Alias added: $label=$id")
    }

    @JvmStatic
    fun addAliases(aliases: Map<String, String>) {
        AppsOnAirPush.aliases.putAll(aliases)
        persistAliases()
    }

    @JvmStatic
    fun removeAlias(label: String) {
        AppsOnAirPush.aliases.remove(label)
        persistAliases()
    }

    @JvmStatic
    fun removeAliases(labels: List<String>) {
        labels.forEach { AppsOnAirPush.aliases.remove(it) }
        persistAliases()
    }

    @JvmStatic
    fun addEmail(address: String) {
        if (address.isBlank() || AppsOnAirPush.emails.contains(address)) return
        AppsOnAirPush.emails.add(address)
        persistEmails()
        AppsOnAirPush.log("Email added: $address")
    }

    @JvmStatic
    fun removeEmail(address: String) {
        AppsOnAirPush.emails.remove(address)
        persistEmails()
    }

    // MARK: - SMS (AOA:Future — not covered in push SDK scope, will be added in a future release)

    // @JvmStatic
    // fun addSms(number: String) {
    //     if (number.isBlank() || AppsOnAirPush.smsNumbers.contains(number)) return
    //     AppsOnAirPush.smsNumbers.add(number)
    //     persistSmsNumbers()
    //     AppsOnAirPush.log("SMS number added: $number")
    // }

    // @JvmStatic
    // fun removeSms(number: String) {
    //     AppsOnAirPush.smsNumbers.remove(number)
    //     persistSmsNumbers()
    // }

    // MARK: - User State Observer

    @JvmStatic
    fun addObserver(observer: IUserStateObserver) {
        AppsOnAirPush.userStateObservers.add(observer)
    }

    @JvmStatic
    fun removeObserver(observer: IUserStateObserver) {
        AppsOnAirPush.userStateObservers.remove(observer)
    }

    private fun persistTags() {
        val json = JSONObject().apply { AppsOnAirPush.tags.forEach { put(it.key, it.value) } }
        AppsOnAirPush.storage.putString("tags_json", json.toString())
    }

    private fun persistAliases() {
        val json = JSONObject().apply { AppsOnAirPush.aliases.forEach { put(it.key, it.value) } }
        AppsOnAirPush.storage.putString("aliases_json", json.toString())
    }

    private fun persistEmails() {
        val json = JSONArray().apply { AppsOnAirPush.emails.forEach { put(it) } }
        AppsOnAirPush.storage.putString("emails_json", json.toString())
    }

    // AOA:Future — SMS persistence (uncomment when SMS support is added)
    // private fun persistSmsNumbers() {
    //     val json = JSONArray().apply { AppsOnAirPush.smsNumbers.forEach { put(it) } }
    //     AppsOnAirPush.storage.putString("sms_json", json.toString())
    // }
}
