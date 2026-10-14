package com.appsonair.apppush

/**
 * Debug logging configuration.
 * Set AppPushService.Debug.logLevel = LogLevel.VERBOSE before initialize() for full logs.
 */
object PushDebug {
    /**
     * Current logging verbosity. Default is WARN, matching iOS: warnings show up in Logcat even
     * before the app sets a level (errors are always logged). The manifest meta-data
     * `com.appsonair.apppush.log_level` (none|fatal|error|warn|info|debug|verbose) sets it
     * without code, read when initialize() runs.
     */
    @JvmField
    var logLevel: LogLevel = LogLevel.WARN

    /** Set the logging verbosity. */
    @JvmStatic
    fun setLogLevel(level: LogLevel) {
        logLevel = level
    }
}
