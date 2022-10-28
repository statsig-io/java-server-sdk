package com.statsig.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI

internal class ErrorBoundary(private val apiKey: String, private val options: StatsigOptions) {
    internal var uri = URI("https://statsigapi.net/v1/sdk_exception")
    private val seen = HashSet<String>()

    private val client = OkHttpClient()
    private companion object {
        val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun <T> swallowSync(task: () -> T) {
        try {
            task()
        } catch (ex: Throwable) {
            onException(ex)
        }
    }

    suspend fun swallow(task: suspend () -> Unit) {
        capture(task) {
            // no-op
        }
    }

    suspend fun <T> capture(task: suspend () -> T, recover: suspend () -> T): T {
        return try {
            task()
        } catch (ex: Throwable) {
            onException(ex)
            recover()
        }
    }

    internal fun logException(ex: Throwable) {
        try {
            if (options.localMode || seen.contains(ex.javaClass.name)) {
                return
            }

            seen.add(ex.javaClass.name)

            val body = """{
                "exception": "${ex.javaClass.name}",
                "info": "${ex.stackTraceToString()}",
                "statsigMetadata": ${StatsigMetadata.asJson()}
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

    private fun onException(ex: Throwable) {
        if (ex is StatsigIllegalStateException ||
            ex is StatsigUninitializedException
        ) {
            throw ex
        }

        println("[Statsig]: An unexpected exception occurred.")
        println(ex)

        logException(ex)
    }
}
