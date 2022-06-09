package com.statsig.sdk

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class ErrorBoundary(private val apiKey: String) {
    internal var uri = URI("https://statsigapi.net/v1/sdk_exception")
    internal val seen = HashSet<String>()

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
            if (seen.contains(ex.javaClass.name)) {
                return
            }

            seen.add(ex.javaClass.name)

            val body = """
            {
                "exception": "${ex.javaClass.name}",
                "info": "${ex.stackTraceToString()}",
                "statsigMetadata": ${StatsigMetadata.asJson()}
            }
        """.trimIndent()
            val client = HttpClient.newBuilder().build()
            val req = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("STATSIG-API-KEY", apiKey).build()
            client.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (_: Throwable) {
            // no-op
        }
    }

    private fun onException(ex: Throwable) {
        if (ex is StatsigIllegalStateException
            || ex is StatsigUninitializedException) {
            throw ex;
        }

        println("[Statsig]: An unexpected exception occurred.")
        println(ex)

        logException(ex)
    }
}