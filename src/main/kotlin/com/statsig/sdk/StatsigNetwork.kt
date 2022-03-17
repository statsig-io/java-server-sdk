package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val statsigHttpClient: OkHttpClient
    private val httpClient: OkHttpClient
    private var lastSyncTime: Long = 0
    private val gson = Gson()

    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object: TypeToken<T>() {}.type)

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
        statsigHttpClient = clientBuilder.build()
        httpClient = OkHttpClient.Builder().build()
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
        statsigHttpClient.newCall(request).await().use { response ->
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
        statsigHttpClient.newCall(request).await().use { response ->
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
            statsigHttpClient.newCall(request).await().use { response ->
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

    private suspend fun downloadIDList(list: IDList, allLists: MutableMap<String, IDList>) {
        if (list.url == null) {
            return
        }
        val request = Request.Builder()
            .url(list.url!!)
            .addHeader("Range", "bytes=${list.size}-")
            .build()

        try {
            httpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val contentLength = response.headers["content-length"]?.toIntOrNull()
                    var content = response.body?.string()
                    if (content == null || content.length <= 1) {
                        return
                    }
                    val firstChar = content[0]
                    if (contentLength == null || (firstChar != '-' && firstChar != '+')) {
                        allLists.remove(list.name)
                        return
                    }
                    val lines = content.lines()
                    for (line in lines) {
                        if (line.length <= 1) {
                            continue
                        }
                        val op = line[0]
                        val id = line.drop(1)
                        if (op == '+') {
                            list.add(id)
                        } else if (op == '-') {
                            list.remove(id)
                        }
                    }
                    list.size = list.size + contentLength
                }
            }
        } catch (e: Exception) {}
    }

    suspend fun getAllIDLists(evaluator: Evaluator) {
        coroutineScope {
            try {
                val bodyJson = gson.toJson(mapOf("statsigMetadata" to statsigMetadata))
                val request: Request = Request.Builder()
                    .url(options.api + "/get_id_lists")
                    .post(bodyJson.toRequestBody(json))
                    .build()
                statsigHttpClient.newCall(request).await().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body ?: return@coroutineScope
                        val response = Gson().fromJson<Map<String, IDList>>(body.string())
                        val allLocalLists = evaluator.idLists
                        for ((name, serverList) in response) {
                            var localList = allLocalLists[name]
                            if (localList == null) {
                                localList = IDList(name=name)
                                allLocalLists[name] = localList
                            }
                            if (serverList.url == null || serverList.fileID == null || serverList.creationTime < localList.creationTime) {
                                continue
                            }

                            // check if fileID has changed and it is indeed a newer file. If so, reset the list
                            if (serverList.fileID != localList.fileID && serverList.creationTime >= localList.creationTime) {
                                localList = IDList(
                                    name=name,
                                    url=serverList.url,
                                    fileID=serverList.fileID,
                                    size=0,
                                    creationTime = serverList.creationTime
                                )
                                allLocalLists[name] = localList
                            }
                            if (serverList.size <= localList.size) {
                                continue
                            }
                            launch {
                                downloadIDList(localList, allLocalLists)
                            }
                        }

                        // remove deleted id lists
                        val deletedLists = mutableListOf<String>()
                        for (name in allLocalLists.keys) {
                            if (!response.containsKey(name)) {
                                deletedLists.add(name)
                            }
                        }
                        for (name in deletedLists) {
                            allLocalLists.remove(name)
                        }
                    }
                }
            } catch (e: Exception) {}
        }
    }

    suspend fun syncIDLists(evaluator: Evaluator) {
        while (true) {
            delay(ID_LISTS_SYNC_INTERVAL_MS)
            getAllIDLists(evaluator)
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
                    statsigHttpClient.newCall(request).await().use { response ->
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
        statsigHttpClient.dispatcher.executorService.shutdown()
        httpClient.dispatcher.executorService.shutdown()
    }
}
