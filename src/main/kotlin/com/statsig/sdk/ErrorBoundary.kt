package com.statsig.sdk

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class ErrorBoundary(private val apiKey: String, private val options: StatsigOptions, private val statsigMetadata: StatsigMetadata) {
    internal var uri = URI("https://statsigapi.net/v1/sdk_exception")
    private val seen = HashSet<String>()
    private val maxInfoLength = 3000

    private val client = OkHttpClient()
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

    suspend fun <T> capture(tag: String, task: suspend () -> T, recover: suspend () -> T, configName: String? = null): T {
        return try {
            task()
        } catch (ex: Throwable) {
            onException(tag, ex, configName)
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

    internal fun logException(tag: String, ex: Throwable, configName: String? = null) {
        try {
            if (options.localMode || seen.contains(ex.javaClass.name)) {
                return
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
                "setupOptions": $optionsCopy
            }
            """.trimIndent()
            val req =
                Request.Builder()
                    .url(uri.toString())
                    .header("STATSIG-API-KEY", apiKey)
                    .post(body.toRequestBody(MEDIA_TYPE))
                    .build()

            client.newCall(req).execute()
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

        println("[Statsig]: An unexpected exception occurred.")
        println(ex)

        logException(tag, ex, configName)
    }
}
