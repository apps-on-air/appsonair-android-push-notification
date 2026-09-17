package com.appsonair.apppush.services

import com.appsonair.apppush.PushDeviceInfo
import com.appsonair.apppush.AppPushService
import com.appsonair.apppush.BuildConfig
import com.appsonair.apppush.LogLevel
import com.appsonair.apppush.utils.StringConst
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object PushApiService {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    internal sealed class Result {
        data class Success(val body: JSONObject) : Result()
        data class Failure(val message: String, val retryable: Boolean) : Result()
    }

    fun post(path: String, body: JSONObject, onResult: (Result) -> Unit) =
        send("POST", path, body, onResult)

    fun post(path: String, body: JSONArray, onResult: (Result) -> Unit) =
        send("POST", path, body, onResult)

    fun patch(path: String, body: JSONObject, onResult: (Result) -> Unit) =
        send("PATCH", path, body, onResult)

    fun get(path: String, onResult: (Result) -> Unit) =
        send("GET", path, JSONObject(), onResult)

    fun delete(path: String, onResult: (Result) -> Unit) =
        send("DELETE", path, JSONObject(), onResult)

    internal var transport: ((String, String, Any, (Result) -> Unit) -> Unit)? = null

    private fun send(method: String, path: String, body: Any, onResult: (Result) -> Unit) {
        transport?.let { return it(method, path, body, onResult) }

        val appId = AppPushService.appId
        if (appId.isBlank()) {
            // Not retryable: the id comes from the manifest and will not appear at runtime.
            onResult(Result.Failure("Missing app id — check the AppsonairAppId manifest entry.", false))
            return
        }

        val url = BuildConfig.BASE_URL + path
        val request = Request.Builder()
            .url(url)
            .addHeader(StringConst.AppIdKey, appId)
            .addHeader(StringConst.SdkVersionKey, PushDeviceInfo.SDK_VERSION)
            .addHeader(StringConst.PlatformKey, StringConst.Platform)
            // GET and DELETE carry no body — OkHttp's Request.Builder.method() throws for
            // GET + non-null body, and the delete endpoint identifies the row by path alone.
            .apply {
                when (method) {
                    "GET" -> get()
                    "DELETE" -> delete()
                    else -> method(method, body.toString().toRequestBody(JSON_MEDIA_TYPE))
                }
            }
            .build()

        AppPushService.log(
            if (method == "GET" || method == "DELETE") "API: $method $url"
            else "API: $method $url\n$body",
            LogLevel.VERBOSE
        )

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                AppPushService.log("API: $method $path failed — ${e.message}", LogLevel.WARN)
                onResult(Result.Failure(e.message ?: "Network error", true))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    val code = it.code
                    AppPushService.log(
                        "API ← $method $path → $code\nresponse body: $raw",
                        LogLevel.VERBOSE
                    )
                    when {
                        it.isSuccessful -> {
                            AppPushService.log("API: $method $path → $code", LogLevel.INFO)
                            onResult(Result.Success(raw.toJsonObjectOrEmpty()))
                        }

                        code in 400..499 -> {
                            AppPushService.log("API: $method $path → $code (not retrying) $raw", LogLevel.ERROR)
                            onResult(Result.Failure("HTTP $code: $raw", false))
                        }
                        else -> {
                            AppPushService.log("API: $method $path → $code (will retry)", LogLevel.WARN)
                            onResult(Result.Failure("HTTP $code: $raw", true))
                        }
                    }
                }
            }
        })
    }

    private fun String.toJsonObjectOrEmpty(): JSONObject =
        runCatching { JSONObject(this) }.getOrElse { JSONObject() }
}
