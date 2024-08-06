package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.statsig.sdk.network.StatsigTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class SpecUpdater(
    private var transport: StatsigTransport,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val diagnostics: Diagnostics,
    private val sdkConfigs: SDKConfigs,
    private val serverSecret: String,
) {
    var lastUpdateTime: Long = 0

    private var configSpecCallback: suspend (config: APIDownloadedConfigs, source: String) -> Unit = { _, _ -> }
    private var idListCallback: suspend (config: Map<String, IDList>) -> Unit = { }
    private var backgroundDownloadConfigs: Job? = null
    private var backgroundDownloadIDLists: Job? = null

    private val gson = Utils.getGson()
    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    fun initialize() {
        transport.initialize()
    }

    fun shutdown() {
        this.backgroundDownloadConfigs?.cancel()
        this.backgroundDownloadIDLists?.cancel()
    }

    fun registerConfigSpecListener(callback: suspend (config: APIDownloadedConfigs, source: String) -> Unit) {
        configSpecCallback = callback
    }
    fun registerIDListsListener(callback: suspend (config: Map<String, IDList>) -> Unit) {
        idListCallback = callback
    }

    fun startListening() {
        if (backgroundDownloadConfigs == null) {
            backgroundDownloadConfigs = statsigScope.launch {
                val (pollMethod, source) = pollForConfigSpecs()
                pollMethod.collect { configSpecCallback(it, source) }
            }
        }
        if (backgroundDownloadIDLists == null) {
            val idListFlow = if (transport.getIDListsWorker.isPullWorker) {
                pollForIDLists()
            } else transport.idListsFlow().map(::parseIDLists).filterNotNull()

            backgroundDownloadIDLists = statsigScope.launch {
                idListFlow.collect { idListCallback(it) }
            }
        }
    }

    suspend fun updateConfigSpecs(): APIDownloadedConfigs? {
        try {
            val response = this.transport.downloadConfigSpecs(this.lastUpdateTime) ?: return null
            val configs = gson.fromJson(response, APIDownloadedConfigs::class.java)
            if (configs.hashedSDKKeyUsed != null && configs.hashedSDKKeyUsed != Hashing.djb2(serverSecret)) {
                return null
            }
            return configs
        } catch (e: Exception) {
            errorBoundary.logException("downloadConfigSpecs", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
        }
        return null
    }

    suspend fun getConfigSpecsFromDataStore(): APIDownloadedConfigs? {
        val dataStore = options.dataStore ?: return null

        val adapterKey = dataStore.dataStoreKey
        val cacheString = dataStore.get(adapterKey)

        val specs = parseConfigSpecs(cacheString)
        specs?.let {
            if (it.time < this.lastUpdateTime) {
                return null
            }
        }
        return specs
    }

    suspend fun updateIDLists(): Map<String, IDList>? {
        val response = transport.getIDLists() ?: return null
        return try {
            gson.fromJson<Map<String, IDList>>(response)
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    fun parseConfigSpecs(specs: String?): APIDownloadedConfigs? {
        if (specs.isNullOrEmpty()) {
            return null
        }
        try {
            return gson.fromJson(specs, APIDownloadedConfigs::class.java)
        } catch (e: JsonSyntaxException) {
            errorBoundary.logException("parseConfigSpecs", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
        }
        return null
    }

    fun parseIDLists(lists: String?): Map<String, IDList>? {
        if (lists.isNullOrEmpty()) {
            return null
        }
        try {
            return gson.fromJson<Map<String, IDList>>(lists)
        } catch (e: JsonSyntaxException) {
            errorBoundary.logException("parseIDLists", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
        }
        return null
    }

    fun pollForConfigSpecs(): Pair<Flow<APIDownloadedConfigs>, String> {
        val (getConfigSpecs, source) = if (options.dataStore?.shouldPollForUpdates() == true) {
            Pair(::getConfigSpecsFromDataStore, "DATA_ADAPTER")
        } else {
            if (!transport.downloadConfigSpecWorker.isPullWorker) {
                return Pair(transport.configSpecsFlow().map(::parseConfigSpecs).filterNotNull(), "NETWORK")
            }
            Pair(::updateConfigSpecs, "NETWORK")
        }

        val configFlow = flow {
            while (true) {
                delay(options.rulesetsSyncIntervalMs)
                val response = getConfigSpecs()
                if (response != null && response.hasUpdates) {
                    emit(response)
                }
            }
        }
        return Pair(configFlow, source)
    }

    private fun pollForIDLists(): Flow<Map<String, IDList>> {
        return flow {
            while (true) {
                delay(options.idListsSyncIntervalMs)
                val response = updateIDLists()
                if (response != null) {
                    emit(response)
                }
            }
        }
    }
}
