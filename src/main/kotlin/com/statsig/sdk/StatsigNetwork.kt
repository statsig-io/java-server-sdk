package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSink
import okio.GzipSink
import okio.buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
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
    private val sdkConfig: SDKConfigs,
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

        clientBuilder.addInterceptor(
            Interceptor {
                val url = it.request().url
                var request = it.request()
                if (url.toString().contains("log_event") && !sdkConfig.getFlag("stop_log_event_compression", false)) {
                    try {
                        request = request.newBuilder().method(
                            request.method,
                            request.body?.let { it1 -> gzip(it1) },
                        ).addHeader("Content-Encoding", "gzip").build()
                    } catch (_: Exception) {
                        // noop: Proceeds with original request
                    }
                }
                it.proceed(request)
            },
        )

        options.proxyConfig?.let {
            setUpProxyAgent(clientBuilder, it)
        }

        statsigHttpClient = clientBuilder.build()
        externalHttpClient = OkHttpClient.Builder().build()
    }

    private fun setUpProxyAgent(clientBuilder: OkHttpClient.Builder, proxyConfig: ProxyConfig) {
        if (proxyConfig.proxyHost.isBlank() || proxyConfig.proxyPort !in 1..65535) {
            options.customLogger.warning("Invalid proxy configuration: Host is blank or port is out of range")
        }

        val proxyAddress = InetSocketAddress(proxyConfig.proxyHost, proxyConfig.proxyPort)
        val proxyType = when (proxyConfig.proxySchema) {
            "http" -> Proxy.Type.HTTP
            "https" -> Proxy.Type.HTTP
            "socks" -> Proxy.Type.SOCKS
            else -> Proxy.Type.HTTP // Default to HTTP if not specified or recognized
        }

        val proxy = Proxy(proxyType, proxyAddress)
        clientBuilder.proxy(proxy)

        proxyConfig.proxyAuth?.let { credentials ->
            val authenticator = Authenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", credentials)
                    .build()
            }

            clientBuilder.proxyAuthenticator(authenticator)
        }
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
        var response = get(
            "$apiForDownloadConfigSpecs/download_config_specs/$sdkKey.json?sinceTime=$sinceTime",
            emptyMap(),
            timeoutMs,
        )
        if (response?.isSuccessful != true && options.fallbackToStatsigAPI && apiForDownloadConfigSpecs != STATSIG_CDN_URL_BASE) {
            // Fallback only when fallbackToStatsigAPI is set to be true, and previous dcs is not hitting Default url
            response = downloadConfigSpecsFromStatsigAPI(sinceTime, timeoutMs)
        }
        return response
    }

    suspend fun downloadConfigSpecsFromStatsigAPI(sinceTime: Long, timeoutMs: Long): Response? {
        return get(
            "$STATSIG_CDN_URL_BASE/download_config_specs/$sdkKey.json?sinceTime=$sinceTime",
            emptyMap(),
            timeoutMs,
        )
    }

    suspend fun downloadIDLists(): Response? {
        var response = post(
            "$api/get_id_lists",
            mapOf("statsigMetadata" to statsigMetadata),
            emptyMap(),
            this.options.initTimeoutMs,
        )
        if (response?.isSuccessful != true && options.fallbackToStatsigAPI && api != STATSIG_API_URL_BASE) {
            response = downloadIDListsFromStatsigAPI()
        }
        return response
    }

    suspend fun downloadIDListsFromStatsigAPI(): Response? {
        return post("$STATSIG_API_URL_BASE/get_id_lists", mapOf("statsigMetadata" to statsigMetadata), emptyMap(), this.options.initTimeoutMs)
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
                var response: Response? = null
                try {
                    response = statsigHttpClient.newCall(request).await()
                    response.use {
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
                } finally {
                    response?.close()
                }

                val count = retries - --currRetry
                delay(backoff * (backoffMultiplier * count) * MS_IN_S)
            }
        }
    }

    fun shutdown() {
        statsigHttpClient.dispatcher.cancelAll()
        statsigHttpClient.dispatcher.executorService.shutdown()
        statsigHttpClient.connectionPool.evictAll()
        statsigHttpClient.cache?.close()
        externalHttpClient.dispatcher.cancelAll()
        externalHttpClient.dispatcher.executorService.shutdown()
        externalHttpClient.connectionPool.evictAll()
        externalHttpClient.cache?.close()
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

    private fun gzip(body: RequestBody): RequestBody? {
        return object : RequestBody() {
            override fun contentType(): MediaType? {
                return body.contentType()
            }

            @Throws(IOException::class)
            override fun writeTo(sink: BufferedSink) {
                val gzipSink: BufferedSink = GzipSink(sink).buffer()
                body.writeTo(gzipSink)
                gzipSink.close()
            }

            override fun contentLength(): Long {
                return -1 // We don't know the compressed length in advance
            }
        }
    }
}
