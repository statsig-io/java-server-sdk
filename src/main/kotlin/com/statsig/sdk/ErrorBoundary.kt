package com.statsig.sdk

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class ErrorBoundary(private val apiKey: String, private val options: StatsigOptions, private val statsigMetadata: StatsigMetadata) {
    internal var uri = URI("https://statsigapi.net/v1/sdk_exception")
    private val seen = HashSet<String>()
    private val maxInfoLength = 3000
    private val client: OkHttpClient by lazy {
        OkHttpClient()
    }
    internal var diagnostics: Diagnostics? = null
    private val coroutineScope = CoroutineScope(this.getNoopExceptionHandler() + Dispatchers.IO)

    private companion object {
        val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun <T> swallowSync(tag: String, task: () -> T) {
        try {
            task()
        } catch (ex: Throwable) {
            onException(tag, ex)
        }
    }

    suspend fun swallow(tag: String, task: suspend () -> Unit) {
        capture(tag, task, {
            // no op
        })
    }

    fun getUrl(): String {
        return uri.toString()
    }

    private fun getNoopExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, _ ->
            // No-op
        }
    }

    suspend fun <T> capture(tag: String, task: suspend () -> T, recover: suspend () -> T, configName: String? = null): T {
        var markerID: String? = null
        var keyType: KeyType? = null
        return try {
            keyType = KeyType.convertFromString(tag)
            markerID = markStart(keyType, configName)
            val result = task()
            markEnd(keyType, true, configName, markerID)
            return result
        } catch (ex: Throwable) {
            onException(tag, ex, configName)
            markEnd(keyType, false, configName, markerID)
            recover()
        }
    }

    fun <T> captureSync(tag: String, task: () -> T, recover: () -> T, configName: String? = null): T {
        return try {
            task()
        } catch (ex: Throwable) {
            onException(tag, ex, configName)
            recover()
        }
    }

    internal fun logException(tag: String, ex: Throwable, configName: String? = null, extraInfo: String? = null, bypassDedupe: Boolean = false) {
        try {
            coroutineScope.launch {
                if (options.localMode || options.disableAllLogging) {
                    return@launch
                }
                if (!bypassDedupe && seen.contains(ex.javaClass.name)) {
                    return@launch
                }
                seen.add(ex.javaClass.name)

                val info = ex.stackTraceToString()
                var safeInfo = URLEncoder.encode(info, StandardCharsets.UTF_8.toString())
                if (safeInfo.length > maxInfoLength) {
                    safeInfo = safeInfo.substring(0, maxInfoLength)
                }
                val optionsCopy = Gson().toJson(options.getLoggingCopy())
                val body = """{
                "tag": "$tag",
                "exception": "${ex.javaClass.name}",
                "info": "$safeInfo",
                "statsigMetadata": ${statsigMetadata.asJson()},
                "configName": "$configName",
                "setupOptions": $optionsCopy,
                "extra": $extraInfo
            }
                """.trimIndent()
                val req =
                    Request.Builder()
                        .url(getUrl())
                        .header("STATSIG-API-KEY", apiKey)
                        .post(body.toRequestBody(MEDIA_TYPE))
                        .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // No-op
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // No-op
                        response.close() // Ensure response is closed to free resources
                    }
                })
            }
        } catch (_: Throwable) {
            // no-op
        }
    }

    private fun onException(tag: String, ex: Throwable, configName: String? = null) {
        if (ex is StatsigIllegalStateException ||
            ex is StatsigUninitializedException
        ) {
            throw ex
        }

        options.customLogger.warning("[Statsig]: An unexpected exception occurred: $ex")

        logException(tag, ex, configName)
    }

    private fun markStart(keyType: KeyType?, configName: String?): String? {
        if (diagnostics == null || keyType == null) {
            return null
        }
        return diagnostics?.markStart(keyType, context = ContextType.API_CALL, additionalMarker = Marker(configName = configName))
    }

    private fun markEnd(keyType: KeyType?, success: Boolean, configName: String?, markerID: String?) {
        if (diagnostics == null || keyType == null) {
            return
        }
        diagnostics?.markEnd(
            keyType,
            success,
            context = ContextType.API_CALL,
            additionalMarker = Marker(markerID = markerID, configName = configName),
        )
    }
}
