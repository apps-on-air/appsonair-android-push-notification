package com.appsonair.apppush

import com.appsonair.apppush.services.PushApiService
import com.appsonair.apppush.utils.StringConst
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit



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

    // One flush at a time: overlapping flushes (foreground, notification tap, registration all
    // trigger one) would send the same head event twice. A flush requested mid-run is not
    // dropped — it re-runs once the current one finishes, so a just-enqueued tap still goes out.
    private var flushing = false
    private var flushRequested = false

    /**
     * Drain the event queue — call on session start / app foreground.
     * Runs on a new background thread (network I/O must NOT block the main thread).
     * Events are sent in FIFO order; stops on first failure to preserve ordering.
     */
    @JvmStatic
    fun flush() {
        synchronized(PushEventQueue) {
            if (flushing) {
                flushRequested = true
                return
            }
            if (load().isEmpty()) return
            flushing = true
            flushRequested = false
        }

        Thread(Thread.currentThread().threadGroup, {
            while (true) {
                drain()
                synchronized(PushEventQueue) {
                    if (!flushRequested) {
                        flushing = false
                        return@Thread
                    }
                    flushRequested = false
                }
            }
        }, "aoa-event-flush").start()
    }

    private fun drain() {
        val snapshot = synchronized(PushEventQueue) { load() }
        if (snapshot.isEmpty()) return

        AppPushService.log("EventQueue: flushing ${snapshot.size} pending event(s).", LogLevel.INFO)

        var sentCount = 0
        for (event in snapshot) {
            if (sendEvent(event)) {
                sentCount++
                AppPushService.log(
                    "EventQueue: sent ${event.type}. remaining=${snapshot.size - sentCount}",
                    LogLevel.DEBUG
                )
            } else {
                // Stop on first failure — preserve ordering, retry on next flush.
                AppPushService.log(
                    "EventQueue: send held/failed, stopping flush. remaining=${snapshot.size - sentCount}",
                    LogLevel.WARN
                )
                break
            }
        }
        // Drop only what was sent from the *current* queue — saving the snapshot back would
        // erase any event enqueued while this flush was on the network.
        synchronized(PushEventQueue) {
            if (sentCount > 0) save(load().drop(sentCount))
        }
    }

    private fun sendEvent(event: PushEvent): Boolean = when (event.type) {
        PushEventType.OPENED    -> postEvent(event, StringConst.EventOpened)
        PushEventType.CLICKED   -> postEvent(event, StringConst.EventClicked)
        PushEventType.DELIVERED -> postEvent(event, StringConst.EventDelivered)
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

    private fun postEvent(event: PushEvent, endpoint: String): Boolean {
        val path = "${StringConst.Events}/$endpoint"
        // A cold-start tap is enqueued before initialize()/registration has a subscription id,
        // so fall back to the current one at send time. Still none (first install, registration
        // not back yet) → hold the event; registration success flushes the queue again.
        val subscriptionId = event.subscriptionId ?: AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log(
                "EventQueue: holding ${event.type} — no subscription id yet. notifId=${event.notificationId}",
                LogLevel.DEBUG
            )
            return false
        }
        val body = JSONObject().apply {
            put(StringConst.SubscriptionIdBodyKey, subscriptionId)
            put(StringConst.EventNotificationIdKey, event.notificationId ?: JSONObject.NULL)
            put(StringConst.EventSendIdKey, event.sendId ?: JSONObject.NULL)
            // Body tap (OPENED) has no action_id — sent as null rather than omitted, so the
            // shape matches the CLICKED request the backend expects. DELIVERED has no action
            // at all, so it carries the other three fields only.
            if (event.type != PushEventType.DELIVERED) {
                put(StringConst.EventActionIdKey, event.actionId ?: JSONObject.NULL)
            }
        }

        AppPushService.log(
            "EventQueue: calling POST /$path. notifId=${event.notificationId} " +
                "subscriptionId=$subscriptionId sendId=${event.sendId} " +
                "type=${event.type} actionId=${event.actionId} request body=$body",
            LogLevel.INFO
        )

        var success = false
        val latch = CountDownLatch(1)
        PushApiService.post(path, body) { result ->
            success = when (result) {
                is PushApiService.Result.Success -> {
                    AppPushService.log(
                        "EventQueue: POST /$path response received. body=${result.body}",
                        LogLevel.INFO
                    )
                    true
                }
                is PushApiService.Result.Failure -> {
                    AppPushService.log(
                        "EventQueue: POST /$path failed — ${result.message}.",
                        LogLevel.WARN
                    )
                    !result.retryable
                }
            }
            latch.countDown()
        }
        latch.await(15, TimeUnit.SECONDS)
        return success
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
                    sendId         = obj.optString("send_id").takeIf { it.isNotEmpty() },
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
                put("send_id",         e.sendId ?: "")
                put("timestamp",       e.timestamp)
                put("device_id",       e.deviceId)
            })
        }
        runCatching { AppPushService.storage.putString(STORAGE_KEY, arr.toString()) }
    }
}
