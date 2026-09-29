package com.appsonair.pushexample

import android.app.Application
import android.util.Log
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.LogLevel

/**
 * initialize() must run before any other SDK call, which is why it lives here rather than in
 * an Activity — Application.onCreate() is the one place guaranteed to run first.
 *
 * There is no appId parameter: AppsOnAir Core reads the "AppsonairAppId" manifest meta-data.
 */
class ExampleApp : Application() {

    companion object {
        /** Set by MainActivity while it is on screen, to show silent pushes in its log. */
        @Volatile
        var silentPushSink: ((Map<String, String>) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()

        // Set the log level BEFORE initialize() or the initialization logs themselves are lost.
        AppPushService.Debug.logLevel = LogLevel.VERBOSE

        AppPushService.initialize(this)

        // Here, not in an Activity: a silent push can wake a killed app with no UI.
        // Runs on a background thread, so blocking work (network, disk) is safe here.
        AppPushService.setSilentPushListener { data ->
            Log.d("ExampleApp", "Silent push received: $data")
            silentPushSink?.invoke(data)
        }
    }
}
