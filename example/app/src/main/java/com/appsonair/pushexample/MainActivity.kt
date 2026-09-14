package com.appsonair.pushexample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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

        btnLogin.setOnClickListener {
            AppPushService.login("user_12345")
            log("login -> externalId=${AppPushService.User.externalId}")
        }
        btnLogout.setOnClickListener {
            AppPushService.logout()
            log("logout -> externalId=${AppPushService.User.externalId ?: "null"}")
        }

        btnAddTag.setOnClickListener {
            AppPushService.User.addTag("plan", "premium")
            log("addTag(plan, premium) — cached locally, synced in the background")
        }
        btnGetTags.setOnClickListener {
            // Reads the backend's copy, not just what this device queued. Main thread.
            AppPushService.User.getTags { tags -> log("getTags -> $tags") }
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
