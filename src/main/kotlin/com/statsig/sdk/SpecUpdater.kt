package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.statsig.sdk.network.StatsigTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val HEALTH_CHECK_INTERVAL_MS = 60_000L

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

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var configSpecCallback: suspend (config: APIDownloadedConfigs, source: DataSource) -> Unit = { _, _ -> }
    private var idListCallback: suspend (config: Map<String, IDList>) -> Unit = { }
    private var backgroundDownloadConfigs: Job? = null
    private var backgroundDownloadIDLists: Job? = null
    private val logger = options.customLogger

    private val gson = Utils.getGson()
    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    fun initialize() {
        transport.setStreamingFallback(NetworkEndpoint.DOWNLOAD_CONFIG_SPECS) {
            this.transport.downloadConfigSpecsFromStatsig(
                this.lastUpdateTime,
            ).first
        }
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
        startBackgroundDcsPolling()
        startBackgroundIDListPolling()
        startPeriodicHealthCheck()
    }

    private fun startPeriodicHealthCheck() {
        monitorScope.launch {
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS) // Check every 60 seconds by default

                if (backgroundDownloadConfigs?.isActive != true) {
                    logger.debug("[StatsigPeriodicHealthCheck] Background polling is inactive. Restarting...")
                    startBackgroundDcsPolling()
                }

                if (backgroundDownloadIDLists?.isActive != true) {
                    logger.debug("[StatsigPeriodicHealthCheck] ID list polling is inactive. Restarting...")
                    startBackgroundIDListPolling()
                }
            }
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

    // This can return a tuple
    suspend fun getConfigSpecs(source: DataSource): Pair<APIDownloadedConfigs?, FailureDetails?> {
        try {
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
        } catch (e: Exception) {
            errorBoundary.logException("getConfigSpec", e)
            return Pair(null, FailureDetails(FailureReason.INTERNAL_ERROR, e))
        }
    }

    private fun getConfigSpecsFromDataStore(): Pair<APIDownloadedConfigs?, FailureDetails?> {
        val dataStore = options.dataStore ?: return Pair(null, FailureDetails(FailureReason.INTERNAL_ERROR))

        val adapterKey = dataStore.dataStoreKey
        val cacheString = dataStore.get(adapterKey)

        val specs = parseConfigSpecs(cacheString)
        specs?.first?.let {
            if (it.time < this.lastUpdateTime) {
                return Pair(null, null)
            }
        }
        return specs
    }

    suspend fun updateIDLists(): Map<String, IDList>? {
        return try {
            val response = transport.getIDLists() ?: return null
            gson.fromJson<Map<String, IDList>>(response)
        } catch (e: JsonSyntaxException) {
            null
        }
    }
    suspend fun getConfigSpecsFromNetwork(): Pair<APIDownloadedConfigs?, FailureDetails?> {
        return parseConfigsFromNetwork(this.transport.downloadConfigSpecs(this.lastUpdateTime))
    }

    /**
     * Starts background polling for config specs.
     * If already running, it will not create a duplicate.
     */
    private fun startBackgroundDcsPolling() {
        if (backgroundDownloadConfigs?.isActive == true) {
            return
        }

        logger.debug("[StatsigSpecUpdater] Initializing new background polling job")

        val flow = if (transport.downloadConfigSpecWorker.isPullWorker) {
            logger.debug("[StatsigSpecUpdater] Using pull worker for config specs syncing")
            pollForConfigSpecs()
        } else {
            logger.debug("[StatsigSpecUpdater] Using streaming for config specs syncing.")
            transport.configSpecsFlow().map(::parseConfigSpecs).map { Pair(it.first, DataSource.NETWORK) }
        }

        backgroundDownloadConfigs = statsigScope.launch {
            flow.collect { response ->
                val spec = response.first
                spec?.let {
                    configSpecCallback(spec, response.second)
                }
            }
        }
    }

    private fun startBackgroundIDListPolling() {
        if (backgroundDownloadIDLists?.isActive == true) {
            return
        }

        val idListFlow = if (transport.getIDListsWorker.isPullWorker) {
            pollForIDLists()
        } else transport.idListsFlow().map(::parseIDLists).filterNotNull()

        backgroundDownloadIDLists = statsigScope.launch {
            idListFlow.collect { idListCallback(it) }
        }
    }

    private fun parseConfigSpecs(specs: String?): Pair<APIDownloadedConfigs?, FailureDetails?> {
        if (specs.isNullOrEmpty()) {
            return Pair(null, FailureDetails(FailureReason.EMPTY_SPEC))
        }
        try {
            return Pair(gson.fromJson(specs, APIDownloadedConfigs::class.java), null)
        } catch (e: JsonSyntaxException) {
            errorBoundary.logException("parseConfigSpecs", e)
            logger.error("[StatsigSpecUpdater] An exception was caught when parsing config specs:  $e")
            return Pair(null, FailureDetails(FailureReason.PARSE_RESPONSE_ERROR, exception = e))
        }
    }

    private fun parseConfigsFromNetwork(response: Pair<String?, FailureDetails?>): Pair<APIDownloadedConfigs?, FailureDetails?> {
        logger.debug("[StatsigSpecUpdater] Start parsing config specs")
        if (response.first == null) {
            logger.debug("[StatsigSpecUpdater] Empty config specs, exiting parseConfigsFromNetwork work.")
            return Pair(null, response.second)
        }
        try {
            val configs = gson.fromJson(response.first, APIDownloadedConfigs::class.java)
            if (configs.hashedSDKKeyUsed != null && configs.hashedSDKKeyUsed != Hashing.djb2(serverSecret)) {
                logger.debug("[StatsigSpecUpdater] Invalidating config specs because sdk key mismatched")
                return Pair(null, FailureDetails(FailureReason.PARSE_RESPONSE_ERROR))
            }
            logger.debug("[StatsigSpecUpdater] Parsed config specs successfully and returning")
            return Pair(configs, null)
        } catch (e: Exception) {
            errorBoundary.logException("downloadConfigSpecs", e)
            logger.warn("[StatsigSpecUpdater] An exception was caught:  $e")
            return Pair(null, FailureDetails(FailureReason.PARSE_RESPONSE_ERROR, exception = e))
        }
    }

    private fun parseIDLists(lists: String?): Map<String, IDList>? {
        if (lists.isNullOrEmpty()) {
            return null
        }
        try {
            return gson.fromJson<Map<String, IDList>>(lists)
        } catch (e: JsonSyntaxException) {
            errorBoundary.logException("parseIDLists", e)
            logger.warn("[StatsigSpecUpdater] An exception was caught when parsing ID lists:  $e")
        }
        return null
    }

    private fun pollForConfigSpecs(): Flow<Pair<APIDownloadedConfigs, DataSource>> {
        val configFlow = flow {
            while (true) {
                delay(options.rulesetsSyncIntervalMs)
                for (source in getConfigSyncSources()) {
                    logger.debug("[StatsigSpecUpdater] Trying fetching config specs from source: $source")
                    val config = getConfigSpecs(source).first
                    if (config != null) {
                        logger.debug("[StatsigSpecUpdater] Successfully fetched config specs from source: $source")
                        emit(Pair(config, source))
                    }
                }
                logger.debug("[StatsigSpecUpdater] Not successfully fetched config specs from source")
            }
        }
        return configFlow
    }

    private fun getConfigSyncSources(): List<DataSource> {
        try {
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
        } catch (e: Exception) {
            errorBoundary.logException("getConfigSyncSources", e)
            return listOf(DataSource.STATSIG_NETWORK)
        }
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
