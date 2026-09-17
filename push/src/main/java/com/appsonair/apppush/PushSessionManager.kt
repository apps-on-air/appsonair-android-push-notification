package com.appsonair.apppush

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.appsonair.apppush.services.PushSessionService


internal object PushSessionManager : DefaultLifecycleObserver {

    @Volatile
    internal var isForeground: Boolean = false
        private set

    fun start() {
        // ProcessLifecycleOwner.get() must run on the main thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            AppPushService.log("SessionManager: started.", LogLevel.DEBUG)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground = true
        // Catches a permission changed in Settings while the app was backgrounded. NOT the only
        // moment it can change: the POST_NOTIFICATIONS dialog leaves the process foregrounded,
        // so onStart never fires for it — AppPushService.onActivityResumed covers that case.
        AppPushService.checkPermissionChange()
        PushEventQueue.flush()
        PushSessionService.handleForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground = false
        PushSessionService.handleBackground()
    }
}
