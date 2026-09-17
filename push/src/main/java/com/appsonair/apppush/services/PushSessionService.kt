package com.appsonair.apppush.services

import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.LogLevel
import com.appsonair.apppush.utils.StringConst
import org.json.JSONObject


internal object PushSessionService {

    private const val KEY_SESSION_ID        = "session_id"
    private const val KEY_SESSION_ENDED_AT  = "session_ended_at"
    private const val KEY_BACKGROUNDED_AT   = "session_backgrounded_at"

    // A background shorter than this is treated as the same session — no new session is
    // started. The previous one was already closed server-side by handleBackground(); that's
    // harmless (session end is idempotent) and nothing else on the client depends on session id.
    private const val NEW_SESSION_THRESHOLD_MS = 30_000L

    private val storage get() = AppPushService.storage

    private var sessionId: String?
        get() = storage.getString(KEY_SESSION_ID)
        set(value) {
            if (value != null) storage.putString(KEY_SESSION_ID, value) else storage.remove(KEY_SESSION_ID)
        }

    // Set locally the moment endSession() sends a close for the current sessionId. Null means
    // the stored sessionId (if any) is still open on the backend — e.g. the process was killed
    // before onStop ever ran to close it. startSession() uses this to detect and close a
    // dangling session left open by a kill, before opening a new one.
    private var sessionEndedAt: Long?
        get() = storage.getLong(KEY_SESSION_ENDED_AT).takeIf { it > 0 }
        set(value) {
            if (value != null) storage.putLong(KEY_SESSION_ENDED_AT, value) else storage.remove(KEY_SESSION_ENDED_AT)
        }

    private var backgroundedAt: Long?
        get() = storage.getLong(KEY_BACKGROUNDED_AT).takeIf { it > 0 }
        set(value) {
            if (value != null) storage.putLong(KEY_BACKGROUNDED_AT, value) else storage.remove(KEY_BACKGROUNDED_AT)
        }

    /**
     * POST /v1/subscriptions already opens a session — adopt the sessionId it returns instead
     * of making a separate POST /v1/sessions call right after registering.
     */
    fun adoptFromRegistration(id: String) {
        sessionId = id
        sessionEndedAt = null // freshly opened — not ended yet
        // Read back rather than logging the argument directly, so this confirms what actually
        // landed in storage rather than just what was passed in.
        AppPushService.log(
            "Session: adopted from registration and persisted. sessionId=$sessionId",
            LogLevel.INFO
        )
    }

    /** Call on app background — closes the current session, if any. */
    fun handleBackground() {
        backgroundedAt = System.currentTimeMillis()
        endSession()
    }

    /**
     * Call before POST /v1/subscriptions on every app open. If a `sessionId` is already stored
     * — left behind by a kill/crash that never made it through [handleBackground] — this closes
     * it with a PATCH /v1/sessions/{id} first (never a POST, so this can never itself start a
     * new session). [onComplete] always runs afterward, whether there was nothing to close, or
     * the close succeeded, failed, or was offline — this cleanup must never block registration.
     */
    fun endStaleSessionIfNeeded(onComplete: () -> Unit) {
        val staleId = sessionId
        if (staleId.isNullOrBlank()) {
            onComplete()
            return
        }
        AppPushService.log(
            "Session: stale session found on reopen — ending before register. sessionId=$staleId",
            LogLevel.WARN
        )
        endSession(onComplete)
    }

    /**
     * Call on app foreground. Starts a new session only if the app was backgrounded for longer
     * than [NEW_SESSION_THRESHOLD_MS]; a quick switch-away keeps the existing session id.
     */
    fun handleForeground() {
        val backgroundedSince = backgroundedAt
        backgroundedAt = null
        if (backgroundedSince == null) return // First foreground since process start — nothing to compare against.

        val elapsedMs = System.currentTimeMillis() - backgroundedSince
        if (elapsedMs <= NEW_SESSION_THRESHOLD_MS) {
            AppPushService.log("Session: foreground after ${elapsedMs}ms — within threshold, not starting a new session.", LogLevel.DEBUG)
            return
        }
        startSession()
    }

    /** POST /v1/sessions — only for an already-registered device; registration itself starts the first session. */
    private fun startSession() {
        val subscriptionId = AppPushService.subscriptionId
        if (subscriptionId.isNullOrBlank()) {
            AppPushService.log("Session: start skipped — no subscription id yet.", LogLevel.DEBUG)
            return
        }

        val staleId = sessionId
        if (!staleId.isNullOrBlank()) {
            if (sessionEndedAt == null) {
                // Never closed locally — the process was killed while this session was still
                // open (onStop/handleBackground() never got the chance to run). Close it out
                // before opening a new one instead of leaving it dangling on the backend.
                AppPushService.log(
                    "Session: dangling session found (app was killed before it could be ended) — closing it first. sessionId=$staleId",
                    LogLevel.WARN
                )
                endSession()
            }
            // Either way, this id is done — drop it locally before adopting the new one.
            sessionId = null
            sessionEndedAt = null
        }

        val body = JSONObject().put(StringConst.SubscriptionIdBodyKey, subscriptionId)
        AppPushService.log("Session: starting. POST ${StringConst.Sessions} request body=$body", LogLevel.INFO)
        PushApiService.post(StringConst.Sessions, body) { result ->
            when (result) {
                is PushApiService.Result.Success -> {
                    AppPushService.log("Session: start response received. body=${result.body}", LogLevel.INFO)

                    val newId = result.body.optString(StringConst.SessionIdResponseKey).takeIf { it.isNotBlank() }
                    if (newId != null) {
                        // Drop the last stored session id and adopt the new one.
                        sessionId = newId
                        sessionEndedAt = null
                        AppPushService.log("Session: started. sessionId=$newId", LogLevel.INFO)
                    } else {
                        AppPushService.log("Session: start response missing sessionId.", LogLevel.WARN)
                    }
                }

                is PushApiService.Result.Failure ->
                    AppPushService.log("Session: start failed — ${result.message}.", LogLevel.WARN)
            }
        }
    }

    /**
     * PATCH /v1/sessions/{sessionId} — best-effort; safe to retry server-side, so no local
     * queue. [onComplete], if given, always runs — whether there was nothing to close, or the
     * close succeeded, failed, or was offline. Callers that must not be blocked by this
     * (registration in particular) rely on that guarantee.
     */
    private fun endSession(onComplete: (() -> Unit)? = null) {
        val id = sessionId
        val subscriptionId = AppPushService.subscriptionId
        if (id.isNullOrBlank() || subscriptionId.isNullOrBlank()) {
            AppPushService.log("Session: end skipped — no active session to close. sessionId=$id subscriptionId=$subscriptionId", LogLevel.DEBUG)
            onComplete?.invoke()
            return
        }

        val endedAt = System.currentTimeMillis() / 1000L
        // Persisted locally regardless of the network outcome below — this is what marks the
        // session as no longer dangling for startSession()'s kill-detection check.
        sessionEndedAt = endedAt

        val body = JSONObject().apply {
            put(StringConst.SubscriptionIdBodyKey, subscriptionId)
            put(StringConst.EndedAtBodyKey, endedAt)
        }

        PushApiService.patch("${StringConst.Sessions}/$id", body) { result ->
            when (result) {
                is PushApiService.Result.Success ->
                    AppPushService.log(
                        "Session: ended. sessionId=$id " +
                            "durationSec=${result.body.optInt(StringConst.DurationSecKey)} " +
                            "counted=${result.body.optBoolean(StringConst.CountedKey, true)}",
                        LogLevel.INFO
                    )

                is PushApiService.Result.Failure ->
                    AppPushService.log("Session: end failed — ${result.message}.", LogLevel.WARN)
            }
            onComplete?.invoke()
        }
    }
}
