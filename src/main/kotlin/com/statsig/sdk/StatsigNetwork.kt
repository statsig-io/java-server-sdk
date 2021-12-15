package com.statsig.sdk

import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.Exception

private const val BACKOFF_MULTIPLIER: Int = 10
private const val MS_IN_S: Long = 1000
internal var CONFIG_SYNC_INTERVAL_MS: Long = 10 * 1000
internal var ID_LISTS_SYNC_INTERVAL_MS: Long = 60 * 1000

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
    private val httpClient: OkHttpClient
    private var lastSyncTime: Long = 0
    private val gson = Gson()

    init {
        val clientBuilder = OkHttpClient.Builder()

        clientBuilder.addInterceptor(Interceptor {
            val original = it.request()
            val request = original.newBuilder()
                .addHeader("STATSIG-API-KEY", sdkKey)
                .addHeader("STATSIG-CLIENT-TIME", System.currentTimeMillis().toString())
                .method(original.method, original.body)
                .build()
            it.proceed(request)
        })
        httpClient = clientBuilder.build()
    }

    suspend fun checkGate(user: StatsigUser?, gateName: String): ConfigEvaluation {
        val bodyJson = gson.toJson(
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
        httpClient.newCall(request).await().use { response ->
            val apiGate = gson.fromJson(response.body?.charStream(), APIFeatureGate::class.java)
            return ConfigEvaluation(fetchFromServer = false, booleanValue = apiGate.value, apiGate.value.toString(), apiGate.ruleID ?: "")
        }
    }

    suspend fun getConfig(user: StatsigUser?, configName: String): ConfigEvaluation {
        val bodyJson = gson.toJson(
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
        httpClient.newCall(request).await().use { response ->
            val apiConfig = gson.fromJson(response.body?.charStream(), APIDynamicConfig::class.java)
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false, apiConfig.value, apiConfig.ruleID ?: "")
        }
    }

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs? {
        val bodyJson = gson.toJson(mapOf("statsigMetadata" to statsigMetadata, "sinceTime" to this.lastSyncTime))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/download_config_specs")
            .post(requestBody)
            .build()
        try {
            httpClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return@use
                }
                val configs = gson.fromJson(response.body?.charStream(), APIDownloadedConfigs::class.java)
                lastSyncTime = configs.time
                return configs
            }
        } catch (e: Exception) {}

        return null
    }

    private suspend fun downloadIDList(listName: String, list: IDList) {
        val bodyJson = gson.toJson(mapOf("listName" to listName, "statsigMetadata" to statsigMetadata, "sinceTime" to list.time))
        val requestBody: RequestBody = bodyJson.toRequestBody(json)

        val request: Request = Request.Builder()
            .url(options.api + "/download_id_list")
            .post(requestBody)
            .build()
        try {
            httpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val response = gson.fromJson(response.body?.charStream(), IDListAPIResponse::class.java)
                    if (response.time > list.time) {
                        response.addIDs.forEach { id -> list.ids[id] = true }
                        response.removeIDs.forEach { id -> list.ids.remove(id) }
                        list.time = response.time
                    }
                }
            }
        } catch (e: Exception) {}
    }

    suspend fun downloadIDLists(evaluator: Evaluator) {
        coroutineScope {
            evaluator.idLists.forEach { entry ->
                launch { downloadIDList(entry.key, entry.value) }
            }
        }
    }

    suspend fun syncIDLists(evaluator: Evaluator) {
        while (true) {
            delay(ID_LISTS_SYNC_INTERVAL_MS)
            downloadIDLists(evaluator)
        }
    }

    fun pollForChanges(): Flow<APIDownloadedConfigs?> {
        return flow {
            while (true) {
                delay(CONFIG_SYNC_INTERVAL_MS)
                val response = downloadConfigSpecs()
                if (response != null) {
                    lastSyncTime = response.time
                    emit(response)
                }
            }
        }
    }

    suspend fun postLogs(events: List<StatsigEvent>, statsigMetadata: Map<String, String>) {
        retryPostLogs(events, statsigMetadata, 5, 1)
    }

    suspend fun retryPostLogs(events: List<StatsigEvent>, statsigMetadata: Map<String, String>, retries: Int, backoff: Int) {
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
                    httpClient.newCall(request).await().use { response ->
                        if (response.isSuccessful) {
                            return@coroutineScope
                        } else if (!retryCodes.contains(response.code) || retries == 0) {
                            return@coroutineScope
                        }
                    }
                } catch (e: Exception) { }

                val count = retries - --currRetry
                delay(backoff * (backoffMultiplier * count) * MS_IN_S)
            }
        }
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
    }
}
