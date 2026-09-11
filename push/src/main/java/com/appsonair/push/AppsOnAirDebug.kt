package com.appsonair.push

/**
 * Debug logging configuration.
 * Set AppsOnAirPush.Debug.logLevel = LogLevel.VERBOSE before initialize() for full logs.
 */
object AppsOnAirDebug {
    /** Current logging verbosity. Default is NONE (no logs in production). */
    @JvmField
    var logLevel: LogLevel = LogLevel.NONE

    /** Set the logging verbosity. */
    @JvmStatic
    fun setLogLevel(level: LogLevel) {
        logLevel = level
    }
}
