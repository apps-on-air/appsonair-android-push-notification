package com.appsonair.push

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner


internal object AppsOnAirSessionManager : DefaultLifecycleObserver {

    @Volatile
    internal var isForeground: Boolean = false
        private set

    fun start() {
        // ProcessLifecycleOwner.get() must run on the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            AppsOnAirPush.log("SessionManager: started.", LogLevel.DEBUG)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        // Catches a permission changed in Settings while the app was backgrounded. NOT the only
        // moment it can change: the POST_NOTIFICATIONS dialog leaves the process foregrounded,
        // so onStart never fires for it — AppsOnAirPush.onActivityResumed covers that case.
        AppsOnAirPush.checkPermissionChange()
        AppsOnAirEventQueue.flush()
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
    }
}
