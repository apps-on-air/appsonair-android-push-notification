package com.appsonair.apppush.utils

internal class StringConst {
    companion object {

        const val Subscriptions = "subscriptions"

        const val Tags = "tags"

        const val TagsKey = "tags"

        const val TagKey = "key"
        const val TagValueKey = "value"

        const val TagsRemove = "remove"

        const val TagKeysKey = "keys"

        const val Alias = "alias"

        const val EmailKey = "email"

        const val OptIn = "opt-in"
        const val OptOut = "opt-out"

        const val OptedIn = "opted-in"

        const val OptedInKey = "opted_in"

        const val Language = "language"

        const val LanguageKey = "language"

        const val AppIdKey = "X-App-Id"
        const val SdkVersionKey = "X-SDK-Version"
        const val PlatformKey = "X-Platform"

        const val Platform = "android"

        const val EnabledKey = "enabled"
        const val PushTokenKey = "push_token"
        const val ExternalIdKey = "external_id"

        const val SubscriptionIdKey = "subscriptionId"

        const val Events = "events"
        const val EventOpened = "opened"
        const val EventClicked = "clicked"
        const val EventDelivered = "delivered"

        // Shared across events/*, sessions/* request bodies.
        const val SubscriptionIdBodyKey = "subscription_id"

        const val EventNotificationIdKey = "notification_id"
        const val EventSendIdKey = "send_id"
        const val EventActionIdKey = "action_id"

        const val Sessions = "sessions"

        const val EndedAtBodyKey = "ended_at"

        const val SessionIdResponseKey = "sessionId"
        const val DurationSecKey = "durationSec"
        const val CountedKey = "counted"
    }
}
