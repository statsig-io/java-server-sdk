package server

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.Exception

private const val POLLING_INTERVAL_MS: Long = 10000
private const val BACKOFF_MULTIPLIER: Int = 10
private const val MS_IN_S: Long = 1000

class StatsigNetwork(
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
    private val httpClient: OkHttpClient
    private var lastSyncTime: Long = 0

    init {
        var clientBuilder = OkHttpClient.Builder()

        clientBuilder.addInterceptor(Interceptor {
            var original = it.request()
            var request = original.newBuilder()
                .addHeader("STATSIG-API-KEY", sdkKey)
                .addHeader("STATSIG-CLIENT-TIME", System.currentTimeMillis().toString())
                .method(original.method, original.body)
                .build()
            it.proceed(request)
        })
        httpClient = clientBuilder.build()
    }

    suspend fun checkGate(user: StatsigUser?, gateName: String): ConfigEvaluation {
        val bodyJson = Gson().toJson(
            mapOf(
                "gateName" to gateName,
                "user" to user,
                "statsigMetadata" to statsigMetadata
            )
        )
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/check_gate")
            .post(requestBody)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val apiGate = Gson().fromJson(response.body?.charStream(), APIFeatureGate::class.java)
            return ConfigEvaluation(fetchFromServer = false, booleanValue = apiGate.value, apiGate.value.toString(), apiGate.ruleID)
        }
    }

    suspend fun getConfig(user: StatsigUser?, configName: String): ConfigEvaluation {
        val bodyJson = Gson().toJson(
            mapOf(
                "configName" to configName,
                "user" to user,
                "statsigMetadata" to statsigMetadata
            )
        )
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/get_config")
            .post(requestBody)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val apiConfig = Gson().fromJson(response.body?.charStream(), APIDynamicConfig::class.java)
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false, apiConfig.value, apiConfig.ruleID)
        }
    }

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs? {
        val bodyJson = Gson().toJson(mapOf("statsigMetadata" to statsigMetadata, "sinceTime" to this.lastSyncTime))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/download_config_specs")
            .post(requestBody)
            .build()
        var network = this
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use
                }
                val configs = Gson().fromJson(response.body?.charStream(), APIDownloadedConfigs::class.java)
                network.lastSyncTime = configs.time
                return configs
            }
        } catch (e: Exception) {}

        return null
    }

    fun pollForChanges(callback: (APIDownloadedConfigs?) -> Unit): Job {
        val network = this
        return CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(POLLING_INTERVAL_MS)
                val response = downloadConfigSpecs()
                if (response != null) {
                    network.lastSyncTime = response.time
                    runBlocking {
                        callback(response)
                    }
                }
            }
        }
    }

    suspend fun postLogs(events: List<StatsigEvent>, statsigMetadata: Map<String, String>) {
        CoroutineScope(Dispatchers.Default).launch {
            retryPostLogs(events, statsigMetadata, 4, 10)
        }
    }

    suspend fun retryPostLogs(events: List<StatsigEvent>, statsigMetadata: Map<String, String>, retries: Int, backoff: Int) {
        if (events.isEmpty()) {
            return
        }
        val bodyJson = Gson().toJson(mapOf("events" to events, "statsigMetadata" to statsigMetadata))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/log_event")
            .post(requestBody)
            .build()
        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.close()
                return
            } else if (!retryCodes.contains(response.code) || retries == 0) {
                response.close()
                return
            }
            response.close()
        } catch (e: Exception) {}

        delay(backoff * MS_IN_S)
        retryPostLogs(events, statsigMetadata, retries - 1, backoff * backoffMultiplier)
    }
}
