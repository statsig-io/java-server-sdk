package com.statsig.sdk.network

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.statsig.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
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
import java.util.*
import java.util.concurrent.TimeUnit

private const val BACKOFF_MULTIPLIER: Int = 10
private const val MS_IN_S: Long = 1000
const val STATSIG_API_URL_BASE: String = "https://statsigapi.net/v1"
const val STATSIG_CDN_URL_BASE: String = "https://api.statsigcdn.com/v1"
const val LOG_EVENT_RETRY_COUNT = 5
const val LOG_EVENT_FAILURE_TAG = "statsig::log_event_failed"
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

internal class HTTPWorker(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigMetadata: StatsigMetadata,
    private val errorBoundary: ErrorBoundary,
    private val sdkConfig: SDKConfigs,
    private val backoffMultiplier: Int = BACKOFF_MULTIPLIER,
    private val httpHelper: HTTPHelper,
) : INetworkWorker {
    override val type = NetworkProtocol.HTTP
    override val isPullWorker: Boolean = true
    override val configSpecsFlow: Flow<String>
        get() = throw NotImplementedError("downloadConfigSpecsFlow not implemented")
    override val idListsFlow: Flow<String>
        get() = throw NotImplementedError("idListsFlow not implemented")
    override fun initializeFlows() {}

    private val json: MediaType = "application/json; charset=utf-8".toMediaType()
    private val statsigHttpClient: OkHttpClient
    private val gson = Utils.getGson()
    private var diagnostics: Diagnostics? = null
    private val logger = options.customLogger
    val apiForDownloadConfigSpecs = options.endpointProxyConfigs[NetworkEndpoint.DOWNLOAD_CONFIG_SPECS]?.proxyAddress ?: options.apiForDownloadConfigSpecs ?: options.api ?: STATSIG_CDN_URL_BASE
    val apiForGetIDLists = options.endpointProxyConfigs[NetworkEndpoint.GET_ID_LISTS]?.proxyAddress ?: options.apiForGetIdlists ?: options.api ?: STATSIG_CDN_URL_BASE // Default to CDN if the user does not override the ID list API endpoint
    val apiForLogEvent = options.endpointProxyConfigs[NetworkEndpoint.LOG_EVENT]?.proxyAddress ?: options.api ?: STATSIG_API_URL_BASE
    private var eventsCount: String = ""

    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    init {
        val clientBuilder = OkHttpClient.Builder()
        clientBuilder.retryOnConnectionFailure(false)

        clientBuilder.addInterceptor(
            Interceptor {
                val original = it.request()
                val request = original.newBuilder()
                    .addHeader("STATSIG-API-KEY", sdkKey)
                    .addHeader("STATSIG-CLIENT-TIME", System.currentTimeMillis().toString())
                    .addHeader("STATSIG-SERVER-SESSION-ID", statsigMetadata.sessionID)
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
    }

    override suspend fun downloadConfigSpecs(sinceTime: Long): Pair<String?, FailureDetails?> {
        var url = "$apiForDownloadConfigSpecs/download_config_specs/$sdkKey.json"
        if (sinceTime > 0) {
            url = "$url?sinceTime=$sinceTime"
        }
        val (response, exception) = get(
            url,
            emptyMap(),
            options.initTimeoutMs,
        )
        response?.use {
            if (!response.isSuccessful) {
                logger.warn("[StatsigHTTPWorker] Failed to download config specification, HTTP Response ${response.code} received from server")
                return Pair(null, FailureDetails(FailureReason.CONFIG_SPECS_NETWORK_ERROR, statusCode = response.code))
            }
            val responseBody = withContext(Dispatchers.IO) { response.body?.string() } // Safe non-blocking read
            return Pair(responseBody, null)
        }
        return Pair(null, FailureDetails(FailureReason.CONFIG_SPECS_NETWORK_ERROR, exception = exception))
    }

    override suspend fun getIDLists(): String? {
        if (apiForGetIDLists == STATSIG_CDN_URL_BASE) {
            return getIDListsFromCDN()
        }
        val response = post(
            "$apiForGetIDLists/get_id_lists",
            mapOf("statsigMetadata" to statsigMetadata),
            emptyMap(),
            this.options.initTimeoutMs,
        )
        response?.use {
            if (!it.isSuccessful) {
                return null
            }
            val responseBody = withContext(Dispatchers.IO) { it.body?.string() } // Safe non-blocking read
            return responseBody
        }
        return null
    }

    suspend fun downloadConfigSpecsFromStatsigAPI(sinceTime: Long): Pair<String?, FailureDetails?> {
        val (response, exception) = get(
            "$STATSIG_CDN_URL_BASE/download_config_specs/$sdkKey.json?sinceTime=$sinceTime",
            emptyMap(),
            options.initTimeoutMs,
        )
        response?.use {
            if (!response.isSuccessful) {
                logger.warn("[StatsigHTTPWorker] Failed to download config specification, HTTP Response ${response.code} received from server")
                return Pair(null, FailureDetails(FailureReason.CONFIG_SPECS_NETWORK_ERROR, statusCode = response.code))
            }
            val responseBody = withContext(Dispatchers.IO) { response.body?.string() } // Safe non-blocking read
            return Pair(responseBody, null)
        }
        return Pair(null, FailureDetails(FailureReason.CONFIG_SPECS_NETWORK_ERROR, exception = exception))
    }

    suspend fun downloadIDListsFromStatsigAPI(): String? {
        val response = post(
            "$STATSIG_API_URL_BASE/get_id_lists",
            mapOf("statsigMetadata" to statsigMetadata),
            emptyMap(),
            this.options.initTimeoutMs,
        )

        response?.use {
            if (!it.isSuccessful) {
                return null
            }
            val responseBody = withContext(Dispatchers.IO) { it.body?.string() } // Safe non-blocking read
            return responseBody
        }
        return null
    }

    override suspend fun logEvents(events: List<StatsigEvent>) {
        retryPostLogs(events, LOG_EVENT_RETRY_COUNT, 1)
    }

    override fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
    }

    override fun shutdown() {
        waitUntilAllRequestsAreFinished(statsigHttpClient.dispatcher)
        statsigHttpClient.dispatcher.cancelAll()
        statsigHttpClient.dispatcher.executorService.shutdown()
        statsigHttpClient.connectionPool.evictAll()
        statsigHttpClient.cache?.close()
    }

    private suspend fun getIDListsFromCDN(): String? {
        val (response, exception) = get(
            "$STATSIG_CDN_URL_BASE/get_id_lists/$sdkKey.json",
            emptyMap(),
            options.initTimeoutMs,
        )

        if (exception != null) {
            logger.warn("[StatsigHTTPWorker] Exception while getting ID lists from CDN: ${exception.message}")
            return null
        }

        response?.use {
            if (!it.isSuccessful) {
                logger.warn("[StatsigHTTPWorker] Failed to get ID lists, HTTP ${it.code} received from CDN")
                return null
            }
            return withContext(Dispatchers.IO) { it.body?.string() }
        }

        return null
    }

    private fun waitUntilAllRequestsAreFinished(dispatcher: okhttp3.Dispatcher) {
        try {
            dispatcher.executorService.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun setUpProxyAgent(clientBuilder: OkHttpClient.Builder, proxyConfig: ProxyConfig) {
        if (proxyConfig.proxyHost.isBlank() || proxyConfig.proxyPort !in 1..65535) {
            logger.warn("[StatsigHTTPWorker] Invalid proxy configuration: Host is blank or port is out of range")
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

    private suspend fun post(
        url: String,
        body: Map<String, Any> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 3000L,
    ): Response? {
        return httpHelper.request(
            statsigHttpClient.newBuilder().callTimeout(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            ).build(),
            url,
            body,
            headers,
        ).first
    }

    private suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 3000L,
    ): Pair<Response?, Exception?> {
        return httpHelper.request(
            statsigHttpClient.newBuilder().callTimeout(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            ).build(),
            url,
            null,
            headers,
        )
    }

    suspend fun retryPostLogs(
        events: List<StatsigEvent>,
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
            .url("$apiForLogEvent/log_event")
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
                            logger.warn("[StatsigHTTPWorker] Network request failed with status code: ${response.code}")
                            logPostLogFailure(eventsCount)
                            return@coroutineScope
                        } else if (retryCodes.contains(response.code) && currRetry > 0) {
                            logger.info("[StatsigHTTPWorker] Retrying network request. Retry count: $currRetry. Response code: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[StatsigHTTPWorker] An exception was caught: $e")
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
