package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

private const val BACKOFF_MULTIPLIER: Int = 10
private const val MS_IN_S: Long = 1000

internal class StatsigNetwork(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigMetadata: Map<String, String>,
    private val backoffMultiplier: Int = BACKOFF_MULTIPLIER
) {
    private val retryCodes: Set<Int> = setOf(
        408,
        500,
        502,
        503,
        504,
        522,
        524,
        599
    )
    private val json: MediaType = "application/json; charset=utf-8".toMediaType()
    private val statsigHttpClient: OkHttpClient
    private val externalHttpClient: OkHttpClient
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private val serverSessionID = UUID.randomUUID().toString()

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
                    .addHeader("STATSIG-SDK-TYPE", statsigMetadata["sdkType"] ?: "")
                    .addHeader("STATSIG-SDK-VERSION", statsigMetadata["sdkVersion"] ?: "")
                    .method(original.method, original.body)
                    .build()
                it.proceed(request)
            }
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
            }
        )

        statsigHttpClient = clientBuilder.build()
        externalHttpClient = OkHttpClient.Builder().build()
    }

    suspend fun checkGate(user: StatsigUser?, gateName: String, disableExposureLogging: Boolean): ConfigEvaluation {
        val exposureLoggingMap = mapOf("exposureLoggingDisabled" to disableExposureLogging)
        val bodyJson = gson.toJson(
            mapOf(
                "gateName" to gateName,
                "user" to user,
                "statsigMetadata" to statsigMetadata + exposureLoggingMap,
            )
        )
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/check_gate")
            .post(requestBody)
            .build()
        statsigHttpClient.newCall(request).await().use { response ->
            val apiGate = gson.fromJson(response.body?.charStream(), APIFeatureGate::class.java)
            return ConfigEvaluation(
                fetchFromServer = false,
                booleanValue = apiGate.value,
                apiGate.value.toString(),
                apiGate.ruleID ?: ""
            )
        }
    }

    suspend fun getConfig(user: StatsigUser?, configName: String, disableExposureLogging: Boolean): ConfigEvaluation {
        val exposureLoggingMap = mapOf("exposureLoggingDisabled" to disableExposureLogging)
        val bodyJson = gson.toJson(
            mapOf(
                "configName" to configName,
                "user" to user,
                "statsigMetadata" to statsigMetadata + exposureLoggingMap,
            )
        )
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/get_config")
            .post(requestBody)
            .build()
        statsigHttpClient.newCall(request).await().use { response ->
            val apiConfig = gson.fromJson(response.body?.charStream(), APIDynamicConfig::class.java)
            return ConfigEvaluation(
                fetchFromServer = false,
                booleanValue = false,
                apiConfig.value,
                apiConfig.ruleID ?: ""
            )
        }
    }

    suspend fun post(
        url: String,
        body: Map<String, Any>?,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        return postImpl(statsigHttpClient, url, body, headers)
    }

    suspend fun postExternal(
        url: String,
        body: Map<String, Any>?,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        return postImpl(externalHttpClient, url, body, headers)
    }

    private suspend fun postImpl(
        client: OkHttpClient,
        url: String,
        body: Map<String, Any>?,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        try {
            var request = Request.Builder()
                .url(url)
            if (body != null) {
                val bodyJson = gson.toJson(body)
                request.post(bodyJson.toRequestBody(json))
            }
            headers.forEach { (key, value) -> request.addHeader(key, value) }
            return client.newCall(request.build()).await()
        } catch (e: Exception) {
            return null
        }
    }

    suspend fun postLogs(events: List<StatsigEvent>, statsigMetadata: Map<String, String>) {
        retryPostLogs(events, statsigMetadata, 5, 1)
    }

    suspend fun retryPostLogs(
        events: List<StatsigEvent>,
        statsigMetadata: Map<String, String>,
        retries: Int,
        backoff: Int
    ) {
        if (events.isEmpty()) {
            return
        }
        val bodyJson = gson.toJson(mapOf("events" to events, "statsigMetadata" to statsigMetadata))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/log_event")
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
                        } else if (!retryCodes.contains(response.code) || retries == 0) {
                            return@coroutineScope
                        }
                    }
                } catch (_: Exception) {
                }

                val count = retries - --currRetry
                delay(backoff * (backoffMultiplier * count) * MS_IN_S)
            }
        }
    }

    fun shutdown() {
        statsigHttpClient.dispatcher.executorService.shutdown()
    }
}
