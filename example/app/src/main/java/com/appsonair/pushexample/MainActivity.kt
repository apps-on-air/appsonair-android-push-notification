package com.appsonair.pushexample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.appsonair.push.AppsOnAirPush
import com.appsonair.push.INotificationClickListener
import com.appsonair.push.NotificationClickEvent
import com.appsonair.push.PushError
import com.appsonair.push.PushListener
import com.appsonair.push.PushNotification
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
        AppsOnAirPush.setListener(this)
        AppsOnAirPush.Notifications.addClickListener(clickListener)

        // Cold start: the app was launched by a notification tap, and the tap data rides in
        // on the launch Intent. Without this the tap is silently dropped.
        AppsOnAirPush.handleNotificationTapIntent(intent)

        log("device id: ${AppsOnAirPush.getDeviceId()}")
        log("subscription: ${AppsOnAirPush.User.pushSubscription.id ?: "(not registered yet)"}")

        wireButtons()
    }

    // Warm start: the app was already in the back stack, so Android delivers the tap here
    // instead of through onCreate().
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { AppsOnAirPush.handleNotificationTapIntent(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppsOnAirPush.setListener(null)
        AppsOnAirPush.Notifications.removeClickListener(clickListener)
    }

    private fun wireButtons() = with(binding) {
        btnRequestPermission.setOnClickListener {
            // No-op below Android 13, and when already granted.
            AppsOnAirPush.requestNotificationPermission(this@MainActivity)
        }
        btnPermissionState.setOnClickListener {
            val granted = AppsOnAirPush.Notifications.permission(this@MainActivity)
            val canAsk = AppsOnAirPush.Notifications.canRequestPermission(this@MainActivity)
            log("permission=$granted canRequest=$canAsk")
        }

        btnLogin.setOnClickListener {
            AppsOnAirPush.login("user_12345")
            log("login -> externalId=${AppsOnAirPush.User.externalId}")
        }
        btnLogout.setOnClickListener {
            AppsOnAirPush.logout()
            log("logout -> externalId=${AppsOnAirPush.User.externalId ?: "null"}")
        }

        btnAddTag.setOnClickListener {
            AppsOnAirPush.User.addTag("plan", "premium")
            log("addTag(plan, premium) — cached locally, synced in the background")
        }
        btnGetTags.setOnClickListener {
            // Reads the backend's copy, not just what this device queued. Main thread.
            AppsOnAirPush.User.getTags { tags -> log("getTags -> $tags") }
        }

        btnOptIn.setOnClickListener {
            AppsOnAirPush.User.pushSubscription.optIn()
            log("optIn -> optedIn=${AppsOnAirPush.User.pushSubscription.optedIn}")
        }
        btnOptOut.setOnClickListener {
            AppsOnAirPush.User.pushSubscription.optOut()
            log("optOut -> optedIn=${AppsOnAirPush.User.pushSubscription.optedIn}")
        }

        btnSetBadge.setOnClickListener {
            AppsOnAirPush.setBadgeCount(this@MainActivity, 5)
            log("setBadgeCount(5) — only some OEM launchers show this")
        }
        btnClearBadge.setOnClickListener {
            AppsOnAirPush.clearBadgeCount(this@MainActivity)
            log("clearBadgeCount()")
        }
        btnClearAll.setOnClickListener {
            AppsOnAirPush.clearAllNotifications(this@MainActivity)
            log("clearAllNotifications()")
        }
    }

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
