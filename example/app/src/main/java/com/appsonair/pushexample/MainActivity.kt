package com.appsonair.pushexample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.INotificationClickListener
import com.appsonair.apppush.NotificationClickEvent
import com.appsonair.apppush.PushError
import com.appsonair.apppush.PushListener
import com.appsonair.apppush.PushNotification
import com.appsonair.pushexample.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every SDK entry point this example demonstrates is wired in onCreate(). The on-screen log
 * shows what the SDK reports back, so a push sent from the console can be followed end to end.
 */
class MainActivity : Activity(), PushListener {

    private lateinit var binding: ActivityMainBinding

    private val clickListener = object : INotificationClickListener {
        override fun onClick(event: NotificationClickEvent) {
            // actionId is null for a body tap, set for an action button.
            log("clicked: ${event.notification.title} action=${event.result.actionId}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Register before anything else so no callback is missed.
        AppPushService.setListener(this)
        AppPushService.Notifications.addClickListener(clickListener)

        // Cold start: the app was launched by a notification tap, and the tap data rides in
        // on the launch Intent. Without this the tap is silently dropped.
        AppPushService.handleNotificationTapIntent(intent)

        log("device id: ${AppPushService.getDeviceId()}")
        log("subscription: ${AppPushService.User.pushSubscription.id ?: "(not registered yet)"}")

        wireButtons()
    }

    // Warm start: the app was already in the back stack, so Android delivers the tap here
    // instead of through onCreate().
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { AppPushService.handleNotificationTapIntent(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppPushService.setListener(null)
        AppPushService.Notifications.removeClickListener(clickListener)
    }

    private fun wireButtons() = with(binding) {
        btnRequestPermission.setOnClickListener {
            // No-op below Android 13, and when already granted.
            AppPushService.requestNotificationPermission(this@MainActivity)
        }
        btnPermissionState.setOnClickListener {
            val granted = AppPushService.Notifications.permission(this@MainActivity)
            val canAsk = AppPushService.Notifications.canRequestPermission(this@MainActivity)
            log("permission=$granted canRequest=$canAsk")
        }

        // Pre-fill with what the SDK already holds, so the fields show the current state.
        etExternalId.setText(AppPushService.User.externalId.orEmpty())
        etLanguage.setText(AppPushService.User.language)

        btnLogin.setOnClickListener {
            val externalId = etExternalId.input() ?: return@setOnClickListener log("login: enter an external ID")
            AppPushService.login(externalId)
            log("login -> externalId=${AppPushService.User.externalId}")
        }
        btnLogout.setOnClickListener {
            AppPushService.logout()
            etExternalId.text.clear()
            log("logout -> externalId=${AppPushService.User.externalId ?: "null"}")
        }

        btnSetLanguage.setOnClickListener {
            val code = etLanguage.input() ?: return@setOnClickListener log("setLanguage: enter a language code")
            AppPushService.User.setLanguage(code)
            log("setLanguage($code) -> language=${AppPushService.User.language}")
        }

        btnAddTag.setOnClickListener {
            val key = etTagKey.input() ?: return@setOnClickListener log("addTag: enter a tag key")
            val value = etTagValue.input() ?: return@setOnClickListener log("addTag: enter a tag value")
            AppPushService.User.addTag(key, value)
            log("addTag($key, $value) — cached locally, synced in the background")
        }
        btnRemoveTag.setOnClickListener {
            val key = etTagKey.input() ?: return@setOnClickListener log("removeTag: enter a tag key")
            AppPushService.User.removeTag(key)
            log("removeTag($key)")
        }
        btnGetTags.setOnClickListener {
            // Reads the backend's copy, not just what this device queued. Main thread.
            AppPushService.User.getTags { tags -> log("getTags -> $tags") }
        }

        btnAddEmail.setOnClickListener {
            val email = etEmail.input() ?: return@setOnClickListener log("addEmail: enter an email")
            // One email per subscription — a second addEmail() replaces the first.
            AppPushService.User.addEmail(email)
            log("addEmail($email)")
        }
        btnRemoveEmail.setOnClickListener {
            val email = etEmail.input() ?: return@setOnClickListener log("removeEmail: enter an email")
            // No-op unless it's the email currently set.
            AppPushService.User.removeEmail(email)
            log("removeEmail($email)")
        }

        btnAddAlias.setOnClickListener {
            val label = etAliasLabel.input() ?: return@setOnClickListener log("addAlias: enter an alias label")
            val id = etAliasId.input() ?: return@setOnClickListener log("addAlias: enter an alias id")
            AppPushService.User.addAlias(label, id)
            log("addAlias($label, $id)")
        }
        btnRemoveAlias.setOnClickListener {
            val label = etAliasLabel.input() ?: return@setOnClickListener log("removeAlias: enter an alias label")
            AppPushService.User.removeAlias(label)
            log("removeAlias($label)")
        }

        btnAddAliases.setOnClickListener {
            val entries = etAliases.aliasEntries()
            val aliases = entries.mapNotNull { entry ->
                val (label, id) = entry.split("=", limit = 2).map { it.trim() }.let {
                    it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty()
                }
                if (label.isEmpty() || id.isEmpty()) null else label to id
            }.toMap()
            if (aliases.isEmpty() || aliases.size != entries.size) {
                return@setOnClickListener log("addAliases: enter label=id pairs, comma-separated")
            }
            // One request for all of them.
            AppPushService.User.addAliases(aliases)
            log("addAliases($aliases)")
        }
        btnRemoveAliases.setOnClickListener {
            // Accepts "label" or "label=id" — only the labels are sent.
            val labels = etAliases.aliasEntries()
                .map { it.substringBefore("=").trim() }
                .filter { it.isNotEmpty() }
            if (labels.isEmpty()) {
                return@setOnClickListener log("removeAliases: enter labels, comma-separated")
            }
            AppPushService.User.removeAliases(labels)
            log("removeAliases($labels)")
        }

        btnOptIn.setOnClickListener {
            AppPushService.User.pushSubscription.optIn()
            log("optIn -> optedIn=${AppPushService.User.pushSubscription.optedIn}")
        }
        btnOptOut.setOnClickListener {
            AppPushService.User.pushSubscription.optOut()
            log("optOut -> optedIn=${AppPushService.User.pushSubscription.optedIn}")
        }

        btnSetBadge.setOnClickListener {
            AppPushService.setBadgeCount(this@MainActivity, 5)
            log("setBadgeCount(5) — only some OEM launchers show this")
        }
        btnClearBadge.setOnClickListener {
            AppPushService.clearBadgeCount(this@MainActivity)
            log("clearBadgeCount()")
        }
        btnClearAll.setOnClickListener {
            AppPushService.clearAllNotifications(this@MainActivity)
            log("clearAllNotifications()")
        }
    }

    /** Comma-separated, non-blank entries of the field. */
    private fun EditText.aliasEntries(): List<String> =
        text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Trimmed field text, or null when blank. */
    private fun EditText.input(): String? = text.toString().trim().takeIf { it.isNotEmpty() }

    // MARK: - PushListener

    override fun onTokenUpdated(token: String) {
        log("token: ${token.take(24)}…")
    }

    override fun onNotificationReceived(notification: PushNotification) {
        log("received: ${notification.title} / ${notification.body}")
    }

    override fun onNotificationOpened(notification: PushNotification) {
        log("opened: id=${notification.id}")
    }

    override fun onError(error: PushError) {
        log("ERROR [${error.code}] ${error.message}")
    }

    // MARK: - Log

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** SDK callbacks already arrive on the main thread; runOnUiThread keeps that explicit. */
    private fun log(line: String) = runOnUiThread {
        binding.tvLog.append("${timeFormat.format(Date())}  $line\n")
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
