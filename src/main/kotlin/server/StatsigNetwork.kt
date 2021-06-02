package server

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.GsonBuilder

private const val POLLING_INTERVAL_MS: Long = 10000
class StatsigNetwork(
        private val sdkKey: String,
        private val options: StatsigOptions,
        private val statsigMetadata: Map<String, String>,
) {
    private val json: MediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient: OkHttpClient
    private var lastSyncTime: Long = 0

    init {
        var clientBuilder = OkHttpClient.Builder()

        clientBuilder.addInterceptor(Interceptor {
            var original = it.request()
            var request = original.newBuilder()
                    .header("STATSIG-API-KEY", sdkKey)
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

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs {
        val bodyJson = Gson().toJson(mapOf("statsigMetadata" to statsigMetadata, "sinceTime" to this.lastSyncTime))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
                .url(options.api + "/download_config_specs")
                .post(requestBody)
                .build()
        var network = this
        httpClient.newCall(request).execute().use { response ->
            val response = Gson().fromJson(response.body?.charStream(), APIDownloadedConfigs::class.java)
            network.lastSyncTime = response.time
            return response
        }
    }

    fun pollForChanges(callback: (APIDownloadedConfigs?) -> Unit): Job {
        val network = this
        return CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(POLLING_INTERVAL_MS)
                val response = downloadConfigSpecs()
                network.lastSyncTime = response.time
                runBlocking {
                    callback(response)
                }
            }
        }
    }

    suspend fun postLogs(events: List<StatsigEvent>, statsigMetadata: Map<String, String>) {
        val bodyJson = Gson().toJson(mapOf("events" to events, "statsigMetadata" to statsigMetadata))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
                .url(options.api + "/log_event")
                .post(requestBody)
                .build()
        httpClient.newCall(request).execute()
    }
}
