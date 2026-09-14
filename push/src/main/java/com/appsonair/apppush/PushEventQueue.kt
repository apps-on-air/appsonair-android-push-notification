package com.appsonair.apppush

import org.json.JSONArray
import org.json.JSONObject



internal object PushEventQueue {

    private const val STORAGE_KEY   = "event_queue_json"
    private const val MAX_QUEUE_SIZE = 100 // Drop oldest if exceeded — prevents unbounded growth

    /**
     * Add an event to the persistent queue.
     * Written to SharedPreferences immediately — survives app restarts.
     * Thread-safe; may be called from any thread.
     */
    @JvmStatic
    @Synchronized
    fun enqueue(event: PushEvent) {
        val queue = load().toMutableList()

        // Cap queue — drop oldest if at limit
        if (queue.size >= MAX_QUEUE_SIZE) {
            // removeAt(0), not removeFirst(): the latter binds to java.util.List#removeFirst,
            // which is API 35+ and throws NoSuchMethodError below it.
            val dropped = queue.removeAt(0)
            AppPushService.log(
                "EventQueue: queue full ($MAX_QUEUE_SIZE), dropped oldest. type=${dropped.type}",
                LogLevel.WARN
            )
        }

        queue.add(event)
        save(queue)
        AppPushService.log(
            "EventQueue: enqueued ${event.type}. " +
            "notifId=${event.notificationId} queueSize=${queue.size}",
            LogLevel.DEBUG
        )
    }

    /**
     * Drain the event queue — call on session start / app foreground.
     * Runs on a new background thread (network I/O must NOT block the main thread).
     * Events are sent in FIFO order; stops on first failure to preserve ordering.
     */
    @JvmStatic
    fun flush() {
        val snapshot = load()
        if (snapshot.isEmpty()) return

        AppPushService.log("EventQueue: flushing ${snapshot.size} pending event(s).", LogLevel.INFO)

        Thread(Thread.currentThread().threadGroup, {
            val remaining = snapshot.toMutableList()
            for (event in snapshot) {
                val sent = sendEvent(event)
                if (sent) {
                    synchronized(PushEventQueue) { remaining.removeAt(0) }
                    AppPushService.log(
                        "EventQueue: sent ${event.type}. remaining=${remaining.size}",
                        LogLevel.DEBUG
                    )
                } else {
                    // Stop on first failure — preserve ordering, retry on next flush.
                    AppPushService.log(
                        "EventQueue: send failed, stopping flush. remaining=${remaining.size}",
                        LogLevel.WARN
                    )
                    break
                }
            }
            synchronized(PushEventQueue) { save(remaining) }
        }, "aoa-event-flush").start()
    }

    private fun sendEvent(event: PushEvent): Boolean = when (event.type) {
        PushEventType.OPENED, PushEventType.CLICKED -> sendOpenEvent(event)
        PushEventType.DELIVERED                     -> sendDeliveryReceipt(event)
        PushEventType.RECEIVED                      -> {
            // Local foreground receipt — no backend call for free tier.
            // TODO: API — POST /events/received if BE wants foreground delivery tracking.
            AppPushService.log(
                "EventQueue: 'RECEIVED' is local-only (no API call). notifId=${event.notificationId}",
                LogLevel.DEBUG
            )
            true
        }
    }

    private fun sendOpenEvent(event: PushEvent): Boolean {
        // TODO: API — POST /events/opened  (or /events/clicked when actionId != null)
        //
        // val url = URL("$baseURL/events/${if (event.actionId != null) "clicked" else "opened"}")
        // val conn = url.openConnection() as HttpURLConnection
        // conn.requestMethod = "POST"
        // conn.setRequestProperty("Content-Type", "application/json")
        // conn.setRequestProperty("Authorization", "Bearer $sdkApiKey")
        // conn.doOutput = true
        // conn.connectTimeout = 10_000
        // conn.readTimeout    = 10_000
        // val body = JSONObject().apply {
        //     put("app_id",          configuredAppId)
        //     put("notification_id", event.notificationId ?: "")
        //     put("subscription_id", event.subscriptionId ?: "")
        //     put("device_id",       event.deviceId)
        //     put("action_id",       event.actionId)  // null = body tap
        //     put("timestamp",       event.timestamp) // epoch ms
        // }.toString().toByteArray()
        // conn.outputStream.write(body)
        // val status = conn.responseCode
        // conn.disconnect()
        // // 2xx → true (remove); 4xx → true (bad data, don't retry); 5xx/timeout → false (retry)
        // return status in 200..299 || status in 400..499
        //
        val endpoint = if (event.actionId != null) "clicked" else "opened"
        AppPushService.log(
            "EventQueue: [TODO] POST /events/$endpoint " +
            "notifId=${event.notificationId} " +
            "subscriptionId=${event.subscriptionId} " +
            "actionId=${event.actionId ?: "(body tap)"}",
            LogLevel.INFO
        )
        return true // Stub — remove when BE API is ready
    }

    private fun sendDeliveryReceipt(event: PushEvent): Boolean {
        // TODO: API — POST /events/delivered
        // Note: Android has no direct equivalent to the iOS Notification Service Extension.
        // Android confirmed delivery may use FCM delivery receipts via the FCM Reporting API.
        // Confirm the exact mechanism with BE before implementing.
        //
        // Body:
        // {
        //   "app_id":          configuredAppId,
        //   "notification_id": event.notificationId,
        //   "subscription_id": event.subscriptionId,
        //   "device_id":       event.deviceId,
        //   "timestamp":       event.timestamp  // epoch ms
        // }
        AppPushService.log(
            "EventQueue: [TODO] POST /events/delivered notifId=${event.notificationId}",
            LogLevel.INFO
        )
        return true // Stub
    }

    private fun load(): List<PushEvent> {
        val json = runCatching { AppPushService.storage.getString(STORAGE_KEY) }.getOrNull()
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PushEvent(
                    type           = PushEventType.valueOf(obj.getString("type")),
                    notificationId = obj.optString("notification_id").takeIf { it.isNotEmpty() },
                    subscriptionId = obj.optString("subscription_id").takeIf { it.isNotEmpty() },
                    actionId       = obj.optString("action_id").takeIf { it.isNotEmpty() },
                    timestamp      = obj.getLong("timestamp"),
                    deviceId       = obj.optString("device_id")
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun save(events: List<PushEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(JSONObject().apply {
                put("type",            e.type.name)
                put("notification_id", e.notificationId ?: "")
                put("subscription_id", e.subscriptionId ?: "")
                put("action_id",       e.actionId ?: "")
                put("timestamp",       e.timestamp)
                put("device_id",       e.deviceId)
            })
        }
        runCatching { AppPushService.storage.putString(STORAGE_KEY, arr.toString()) }
    }
}
