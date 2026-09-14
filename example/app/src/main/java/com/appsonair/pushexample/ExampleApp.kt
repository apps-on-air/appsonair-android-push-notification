package com.appsonair.pushexample

import android.app.Application
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.LogLevel

/**
 * initialize() must run before any other SDK call, which is why it lives here rather than in
 * an Activity — Application.onCreate() is the one place guaranteed to run first.
 *
 * There is no appId parameter: AppsOnAir Core reads the "AppsonairAppId" manifest meta-data.
 */
class ExampleApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Set the log level BEFORE initialize() or the initialization logs themselves are lost.
        AppPushService.Debug.logLevel = LogLevel.VERBOSE

        AppPushService.initialize(this)
    }
}
