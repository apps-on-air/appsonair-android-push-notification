package com.appsonair.apppush

import android.content.Context
import android.os.Build
import com.appsonair.core.services.CoreService
import com.appsonair.apppush.utils.StringConst
import java.io.File


internal object PushDeviceInfo {

    // MARK: - SDK version

    /** SDK version — bump on every release. */
    const val SDK_VERSION = "0.0.2-alpha"

    /** Heuristic root check — segmentation only, NOT a security guarantee. */
    val isRooted: Boolean
        get() {
            if (Build.TAGS?.contains("test-keys") == true) return true
            val suPaths = listOf(
                "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
                "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
                "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su"
            )
            return suPaths.any { File(it).exists() }
        }

    fun registrationPayload(context: Context): Map<String, Any> {
        val metadata = CoreService.getDeviceMetadata(context)
        val payload: MutableMap<String, Any> = mutableMapOf(
            // Identity — the backend keys the subscription off these.
            "app_id"         to AppPushService.appId,
            "device_id"      to metadata.optString("deviceId"),
            "platform"       to StringConst.Platform,
            "push_token"     to (AppPushService.storage.getFcmToken() ?: ""),
            // OS notification permission — not the app's opt-out, which is is_opted_out below.
            "enabled"        to PushNotifications.permission(context),
            "sdk_version"    to SDK_VERSION,
            "app_version"    to metadata.optString("appVersion"),
            "build_number"   to metadata.optInt("buildVersionNumber"),
            // Core reports model and manufacturer separately; the wire format keeps them joined.
            "device_model"   to "${metadata.optString("manufacturer")} ${metadata.optString("deviceModel")}".trim(),
            "os_version"     to metadata.optString("osVersion"),
            "api_level"      to metadata.optInt("apiLevel"),
            "timezone"       to metadata.optString("timezone"),
            // Device *locale* region ("US"), deliberately not geo-IP — see
            // PUSH-SUBSCRIPTION-FIELD-GAP.md §4.4.
            "country"        to metadata.optString("regionCode"),
            "language"       to AppPushService.language,
            "is_test_device" to AppPushService.isTestDevice,
            "is_rooted"      to isRooted,
            "is_simulator"   to metadata.optBoolean("isSimulator"),        // (extra)
            "first_install_time" to metadata.optString("firstInstallTime"),// (extra)
            "is_opted_out"   to AppPushService.isOptedOut                   // (extra)
        )
        AppPushService.log("DeviceInfo: registration payload assembled.", LogLevel.VERBOSE)
        return payload
    }
}
