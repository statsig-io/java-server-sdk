package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.statsig.sdk.network.StatsigTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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

    private var configSpecCallback: suspend (config: APIDownloadedConfigs, source: DataSource) -> Unit = { _, _ -> }
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

    fun registerConfigSpecListener(callback: suspend (config: APIDownloadedConfigs, source: DataSource) -> Unit) {
        configSpecCallback = callback
    }
    fun registerIDListsListener(callback: suspend (config: Map<String, IDList>) -> Unit) {
        idListCallback = callback
    }

    fun startListening() {
        if (backgroundDownloadConfigs == null) {
            val flow = if (transport.downloadConfigSpecWorker.isPullWorker) { pollForConfigSpecs() } else {
                transport.configSpecsFlow().map(::parseConfigSpecs).filterNotNull().map { Pair(it, DataSource.NETWORK) }
            }

            backgroundDownloadConfigs = statsigScope.launch {
                flow.collect {
                    configSpecCallback.invoke(it.first, it.second)
                }
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
        transport.setStreamingFallback(NetworkEndpoint.DOWNLOAD_CONFIG_SPECS) {
            this.transport.downloadConfigSpecsFromStatsig(
                this.lastUpdateTime,
            )
        }
    }

    fun getInitializeOrder(): List<DataSource> {
        val optionsOrder = options.initializeSources
        if (optionsOrder != null) return optionsOrder
        val order = mutableListOf<DataSource>()
        if (options.dataStore != null) {
            order.add(DataSource.DATA_STORE)
        } else if (options.bootstrapValues != null) {
            order.add(DataSource.BOOTSTRAP)
        }
        order.add(DataSource.NETWORK)
        if (options.fallbackToStatsigAPI) {
            order.add(DataSource.STATSIG_NETWORK)
        }
        return order
    }

    suspend fun getConfigSpecs(source: DataSource): APIDownloadedConfigs? {
        return when (source) {
            DataSource.NETWORK -> getConfigSpecsFromNetwork()
            DataSource.STATSIG_NETWORK -> parseConfigsFromNetwork(this.transport.downloadConfigSpecsFromStatsig(this.lastUpdateTime))
            DataSource.DATA_STORE -> {
                getConfigSpecsFromDataStore()
            }
            DataSource.BOOTSTRAP -> {
                diagnostics.markStart(KeyType.BOOTSTRAP, step = StepType.PROCESS)
                parseConfigSpecs(this.options.bootstrapValues)
            }
        }
    }

    private fun getConfigSpecsFromDataStore(): APIDownloadedConfigs? {
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
    suspend fun getConfigSpecsFromNetwork(): APIDownloadedConfigs? {
        return parseConfigsFromNetwork(this.transport.downloadConfigSpecs(this.lastUpdateTime))
    }

    private fun parseConfigSpecs(specs: String?): APIDownloadedConfigs? {
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

    private fun parseConfigsFromNetwork(response: String?): APIDownloadedConfigs? {
        if (response == null) return null
        try {
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

    private fun parseIDLists(lists: String?): Map<String, IDList>? {
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

    private fun pollForConfigSpecs(): Flow<Pair<APIDownloadedConfigs, DataSource>> {
        val configFlow = flow {
            while (true) {
                delay(options.rulesetsSyncIntervalMs)
                for (source in getConfigSyncSources()) {
                    val config = getConfigSpecs(source)
                    if (config != null) {
                        emit(Pair(config, source))
                    }
                }
            }
        }
        return configFlow
    }

    private fun getConfigSyncSources(): List<DataSource> {
        val configSpecSources = options.configSyncSources
        if (configSpecSources != null) {
            return configSpecSources
        }
        val sources = mutableListOf<DataSource>()
        if (options.dataStore?.shouldPollForUpdates() == true) {
            sources.add(DataSource.DATA_STORE)
        } else {
            sources.add(DataSource.NETWORK)
        }
        if (options.fallbackToStatsigAPI) {
            sources.add(DataSource.STATSIG_NETWORK)
        }
        return sources
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
