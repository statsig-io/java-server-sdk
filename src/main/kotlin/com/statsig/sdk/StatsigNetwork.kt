package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val BACKOFF_MULTIPLIER: Int = 10
private const val MS_IN_S: Long = 1000
const val STATSIG_API_URL_BASE: String = "https://statsigapi.net/v1"
private const val STATSIG_CDN_URL_BASE: String = "https://api.statsigcdn.com/v1"
const val LOG_EVENT_RETRY_COUNT = 5
const val LOG_EVENT_FAILURE_TAG = "statsig::log_event_failed"

internal class StatsigNetwork(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigMetadata: StatsigMetadata,
    private val errorBoundary: ErrorBoundary,
    private val backoffMultiplier: Int = BACKOFF_MULTIPLIER,
) {
    private val retryCodes: Set<Int> = setOf(
        408,
        500,
        502,
        503,
        504,
        522,
        524,
        599,
    )
    private val json: MediaType = "application/json; charset=utf-8".toMediaType()
    private val statsigHttpClient: OkHttpClient
    private val externalHttpClient: OkHttpClient
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val serverSessionID = UUID.randomUUID().toString()
    private var diagnostics: Diagnostics? = null
    private val api = options.api ?: STATSIG_API_URL_BASE
    private val apiForDownloadConfigSpecs = options.api ?: STATSIG_CDN_URL_BASE
    private var eventsCount: String = ""

    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    init {
        val clientBuilder = OkHttpClient.Builder()

        clientBuilder.addInterceptor(
            Interceptor {
                val original = it.request()
                val request = original.newBuilder()
                    .addHeader("STATSIG-API-KEY", sdkKey)
                    .addHeader("STATSIG-CLIENT-TIME", System.currentTimeMillis().toString())
                    .addHeader("STATSIG-SERVER-SESSION-ID", serverSessionID)
                    .addHeader("STATSIG-SDK-TYPE", statsigMetadata.sdkType)
                    .addHeader("STATSIG-SDK-VERSION", statsigMetadata.sdkVersion)
                    .addHeader("STATSIG-EVENT-COUNT", eventsCount)
                    .method(original.method, original.body)
                    .build()
                it.proceed(request)
            },
        )

        clientBuilder.addInterceptor(
            Interceptor {
                if (options.localMode) {
                    return@Interceptor Response.Builder().code(200)
                        .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                        .protocol(Protocol.HTTP_2)
                        .request(it.request())
                        .message("Request blocked due to localMode being active")
                        .build()
                }
                it.proceed(it.request())
            },
        )

        statsigHttpClient = clientBuilder.build()
        externalHttpClient = OkHttpClient.Builder().build()
    }

    fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
    }

    suspend fun checkGate(user: StatsigUser?, gateName: String, disableExposureLogging: Boolean): ConfigEvaluation {
        statsigMetadata.exposureLoggingDisabled = disableExposureLogging
        val bodyJson = gson.toJson(
            mapOf(
                "gateName" to gateName,
                "user" to user,
                "statsigMetadata" to statsigMetadata,
            ),
        )
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url("$api/check_gate")
            .post(requestBody)
            .build()
        statsigHttpClient.newCall(request).await().use { response ->
            val apiGate = gson.fromJson(response.body?.charStream(), APIFeatureGate::class.java)
            return ConfigEvaluation(
                booleanValue = apiGate.value,
                apiGate.value.toString(),
                apiGate.ruleID ?: "",
            )
        }
    }

    suspend fun getConfig(user: StatsigUser?, configName: String, disableExposureLogging: Boolean): ConfigEvaluation {
        statsigMetadata.exposureLoggingDisabled = disableExposureLogging
        val bodyJson = gson.toJson(
            mapOf(
                "configName" to configName,
                "user" to user,
                "statsigMetadata" to statsigMetadata,
            ),
        )
        statsigMetadata.exposureLoggingDisabled = null
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url("$api/get_config")
            .post(requestBody)
            .build()
        statsigHttpClient.newCall(request).await().use { response ->
            val apiConfig = gson.fromJson(response.body?.charStream(), APIDynamicConfig::class.java)
            return ConfigEvaluation(
                booleanValue = false,
                apiConfig.value,
                apiConfig.ruleID ?: "",
            )
        }
    }

    suspend fun post(
        url: String,
        body: Map<String, Any> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 3000L,
    ): Response? {
        return request(
            statsigHttpClient.newBuilder().callTimeout(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            ).build(),
            url,
            body,
            headers,
        )
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 3000L,
    ): Response? {
        return request(
            statsigHttpClient.newBuilder().callTimeout(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            ).build(),
            url,
            null,
            headers,
        )
    }

    suspend fun downloadConfigSpecs(sinceTime: Long, timeoutMs: Long): Response? {
        return get(
            "$apiForDownloadConfigSpecs/download_config_specs/$sdkKey.json?sinceTime=$sinceTime",
            emptyMap(),
            timeoutMs,
        )
    }

    suspend fun getExternal(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        return request(externalHttpClient, url, null, headers)
    }

    private suspend fun request(
        client: OkHttpClient,
        url: String,
        body: Map<String, Any>?,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        val diagnosticsKey = diagnostics?.getDiagnosticKeyFromURL(url)
        try {
            val request = Request.Builder()
                .url(url)
            if (body != null) {
                val bodyJson = gson.toJson(body)
                request.post(bodyJson.toRequestBody(json))
            }
            headers.forEach { (key, value) -> request.addHeader(key, value) }
            diagnostics?.startNetworkRequestDiagnostics(diagnosticsKey)
            val response = client.newCall(request.build()).await()
            diagnostics?.endNetworkRequestDiagnostics(diagnosticsKey, response.isSuccessful, response)
            return response
        } catch (e: Exception) {
            options.customLogger.warning("[Statsig]: An exception was caught: $e")
            if (e is JsonParseException) {
                errorBoundary.logException("postImpl", e)
            }
            diagnostics?.endNetworkRequestDiagnostics(diagnosticsKey, false, null)
            return null
        }
    }

    suspend fun postLogs(events: List<StatsigEvent>, statsigMetadata: StatsigMetadata) {
        retryPostLogs(events, statsigMetadata, LOG_EVENT_RETRY_COUNT, 1)
    }

    suspend fun retryPostLogs(
        events: List<StatsigEvent>,
        statsigMetadata: StatsigMetadata,
        retries: Int,
        backoff: Int,
    ) {
        if (events.isEmpty()) {
            return
        }
        val bodyJson = gson.toJson(mapOf("events" to events, "statsigMetadata" to statsigMetadata))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)
        eventsCount = events.size.toString()

        val request: Request = Request.Builder()
            .url("$api/log_event")
            .post(requestBody)
            .build()
        coroutineScope { // Creates a coroutine scope to be used within this suspend function
            var currRetry = retries
            while (true) {
                ensureActive() // Quick check to ensure the coroutine isn't cancelled
                try {
                    statsigHttpClient.newCall(request).await().use { response ->
                        if (response.isSuccessful) {
                            return@coroutineScope
                        } else if (!retryCodes.contains(response.code) || currRetry == 0) {
                            options.customLogger.warning("[Statsig]: Network request failed with status code: ${response.code}")
                            logPostLogFailure(eventsCount)
                            return@coroutineScope
                        } else if (retryCodes.contains(response.code) && currRetry > 0) {
                            options.customLogger.info("[Statsig]: Retrying network request. Retry count: $currRetry. Response code: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    options.customLogger.warning("[Statsig]: An exception was caught: $e")
                    if (e is JsonParseException) {
                        errorBoundary.logException("retryPostLogs", e)
                    }
                    if (currRetry == 0) {
                        logPostLogFailure(eventsCount)
                        return@coroutineScope
                    }
                }

                val count = retries - --currRetry
                delay(backoff * (backoffMultiplier * count) * MS_IN_S)
            }
        }
    }

    fun shutdown() {
        statsigHttpClient.dispatcher.executorService.shutdown()
    }

    private fun logPostLogFailure(eventsCount: String) {
        errorBoundary.logException(
            LOG_EVENT_FAILURE_TAG,
            Exception("Drop log event"),
            null,
            extraInfo = """{
               "eventCount": $eventsCount
            }
            """.trimIndent(),
            bypassDedupe = true,
        )
    }
}
