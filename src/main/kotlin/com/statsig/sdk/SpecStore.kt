package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.statsig.sdk.datastore.IDataStore
import com.statsig.sdk.network.StatsigTransport
import kotlinx.coroutines.*
import okhttp3.Response

const val STORAGE_ADAPTER_KEY = "statsig.cache"

internal class SpecStore(
    private var transport: StatsigTransport,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val diagnostics: Diagnostics,
    private val sdkConfigs: SDKConfigs,
    private val serverSecret: String,
) {
    private var initTime: Long = 0
    private var evalReason: EvaluationReason = EvaluationReason.UNINITIALIZED
    private var downloadIDListCallCount: Long = 0

    private var dynamicConfigs: Map<String, APIConfig> = emptyMap()
    private var gates: Map<String, APIConfig> = emptyMap()
    private var layers: Map<String, Array<String>> = HashMap()
    private var idLists: MutableMap<String, IDList> = HashMap()
    private var sdkKeysToAppIDs: Map<String, String> = HashMap()
    private var hashedSDKKeysToAppIDs: Map<String, String> = HashMap()
    private var hashedSDKKeysToEntities: Map<String, APIEntityNames> = HashMap()
    private var primaryTargetAppID: String? = null

    private var layerConfigs: Map<String, APIConfig> = emptyMap()
    private var experimentToLayer: Map<String, String> = emptyMap()
    private val logger = options.customLogger

    private var specUpdater = SpecUpdater(transport, options, statsigMetadata, statsigScope, errorBoundary, diagnostics, sdkConfigs, serverSecret)
    init {
        specUpdater.registerIDListsListener(::processDownloadedIDLists)
        specUpdater.registerConfigSpecListener(::processDownloadedConfigs)
    }

    private val gson = Utils.getGson()
    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    suspend fun initialize(): FailureDetails? {
        if (!options.localMode) {
            specUpdater.initialize()

            val failureDetails = this.initializeSpecs()
            this.initTime = if (specUpdater.lastUpdateTime == 0L) -1 else specUpdater.lastUpdateTime

            this.syncIdListsFromNetwork(specUpdater.updateIDLists())
            specUpdater.startListening()

            return failureDetails
        }
        return null
    }

    fun shutdown() {
        if (this.options.localMode) {
            return
        }
        this.specUpdater.shutdown()
    }

    suspend fun syncConfigSpecs(): ConfigSyncDetails {
        val startTime = System.currentTimeMillis()
        val failureDetails = initializeSpecs()
        val endTime = System.currentTimeMillis()
        return ConfigSyncDetails(
            duration = endTime - startTime,
            configSpecReady = failureDetails == null,
            failureDetails,
            specUpdater.lastUpdateTime
        )
    }

    fun setDownloadedConfigs(downloadedConfig: APIDownloadedConfigs, isFromBootstrap: Boolean = false): Boolean {
        if (!downloadedConfig.hasUpdates) {
            logger.debug("[StatsigSpecStore] Downloaded config specs has no updates.")
            return false
        }
        if (downloadedConfig.time < specUpdater.lastUpdateTime) {
            logger.debug("[StatsigSpecStore] No need to update since last update time is greater than dcs time.")
            return false
        }
        if (options.dataStore == null && !isFromBootstrap) {
            diagnostics.markStart(KeyType.DOWNLOAD_CONFIG_SPECS, step = StepType.PROCESS)
        }
        val newGates = getParsedSpecs(downloadedConfig.featureGates)
        val newDynamicConfigs = getParsedSpecs(downloadedConfig.dynamicConfigs)
        val newLayerConfigs = getParsedSpecs(downloadedConfig.layerConfigs)

        val newExperimentToLayer = emptyMap<String, String>().toMutableMap()
        val layersMap = downloadedConfig.layers
        if (layersMap != null) {
            for (layerName in layersMap.keys) {
                val experiments = layersMap[layerName] ?: continue
                for (experimentName in experiments) {
                    newExperimentToLayer[experimentName] = layerName
                }
            }
        }

        this.gates = newGates
        this.dynamicConfigs = newDynamicConfigs
        this.layerConfigs = newLayerConfigs
        this.experimentToLayer = newExperimentToLayer
        specUpdater.lastUpdateTime = downloadedConfig.time
        this.sdkKeysToAppIDs = downloadedConfig.sdkKeysToAppIDs ?: mapOf()
        this.hashedSDKKeysToAppIDs = downloadedConfig.hashedSDKKeysToAppIDs ?: mapOf()
        this.hashedSDKKeysToEntities = downloadedConfig.hashedSDKKeysToEntities ?: mapOf()
        this.primaryTargetAppID = downloadedConfig.primaryTargetAppID

        if (downloadedConfig.diagnostics != null) {
            diagnostics.setSamplingRate(downloadedConfig.diagnostics)
        }
        if (downloadedConfig.sdkConfigs != null) {
            sdkConfigs.setConfigs(downloadedConfig.sdkConfigs)
        }
        if (downloadedConfig.sdkFlags != null) {
            sdkConfigs.setFlags(downloadedConfig.sdkFlags)
        }
        if (options.dataStore == null && !isFromBootstrap) {
            diagnostics.markEnd(KeyType.DOWNLOAD_CONFIG_SPECS, true, StepType.PROCESS)
        }
        logger.debug("[StatsigSpecStore] Successfully set spec store using the newer download config specs.")
        return true
    }

    fun getGate(name: String): APIConfig? {
        return this.gates[name]
    }

    fun getAllGates(): Map<String, APIConfig> {
        return this.gates
    }

    fun getConfig(name: String): APIConfig? {
        return this.dynamicConfigs[name]
    }

    fun getAllConfigs(): Map<String, APIConfig> {
        return this.dynamicConfigs
    }

    fun getLayerConfig(name: String): APIConfig? {
        return this.layerConfigs[name]
    }

    fun getAllLayerConfigs(): Map<String, APIConfig> {
        return this.layerConfigs
    }

    fun getLayer(name: String): Array<String>? {
        return this.layers[name]
    }

    fun getAllLayers(): Map<String, Array<String>> {
        return this.layers
    }

    fun getLayerNameForExperiment(experimentname: String): String? {
        return this.experimentToLayer[experimentname]
    }

    fun getIDList(idListName: String): IDList? {
        return this.idLists[idListName]
    }

    fun getInitTime(): Long {
        return this.initTime
    }

    fun getEvaluationReason(): EvaluationReason {
        return this.evalReason
    }

    fun getLastUpdateTime(): Long {
        return specUpdater.lastUpdateTime
    }

    fun getAppIDFromKey(clientSDKKey: String): String? {
        if (this.hashedSDKKeysToAppIDs.containsKey(Hashing.djb2(clientSDKKey))) {
            return this.hashedSDKKeysToAppIDs[Hashing.djb2(clientSDKKey)]
        }
        return this.sdkKeysToAppIDs[clientSDKKey]
    }

    fun getEntitiesFromKey(clientSDKKey: String): APIEntityNames? {
        return this.hashedSDKKeysToEntities[Hashing.djb2(clientSDKKey)]
    }

    fun getPrimaryTargetAppID(): String? {
        return this.primaryTargetAppID
    }

    private fun fireRulesUpdatedCallback(configSpecs: APIDownloadedConfigs) {
        if (options.rulesUpdatedCallback == null) {
            return
        }

        var configString = ""
        try {
            configString = gson.toJson(configSpecs)
        } catch (e: Exception) {
            errorBoundary.logException("fireRulesUpdatedCallback", e)
            logger.error("An exception was caught when fire callback:  $e")
        }

        if (configString.isEmpty()) {
            return
        }

        options.rulesUpdatedCallback?.accept(configString)
    }

    private suspend fun syncIdListsFromNetwork(idListResponse: Map<String, IDList>?) {
        if (idListResponse == null) return
        try {
            diagnostics.markStart(
                KeyType.GET_ID_LIST_SOURCES,
                StepType.PROCESS,
                additionalMarker = Marker(idListCount = idListResponse.size),
            )
            val tasks = mutableListOf<Job>()

            for ((name, serverList) in idListResponse) {
                var localList = idLists[name]
                if (localList == null) {
                    localList = IDList(name = name)
                    idLists[name] = localList
                }
                if (serverList.url == null || serverList.fileID == null || serverList.creationTime < localList.creationTime) {
                    continue
                }

                // check if fileID has changed, and it is indeed a newer file. If so, reset the list
                if (serverList.fileID != localList.fileID && serverList.creationTime >= localList.creationTime) {
                    localList = IDList(
                        name = name,
                        url = serverList.url,
                        fileID = serverList.fileID,
                        size = 0,
                        creationTime = serverList.creationTime,
                    )
                    idLists[name] = localList
                }
                if (serverList.size <= localList.size) {
                    continue
                }

                val curCount = ++downloadIDListCallCount
                tasks.add(
                    statsigScope.launch {
                        downloadIDList(localList, curCount)
                    },
                )
            }

            tasks.joinAll()
            diagnostics.markEnd(KeyType.GET_ID_LIST_SOURCES, true, StepType.PROCESS)

            // remove deleted id lists
            val deletedLists = mutableListOf<String>()
            for (name in idLists.keys) {
                if (!idListResponse.containsKey(name)) {
                    deletedLists.add(name)
                }
            }
            for (name in deletedLists) {
                idLists.remove(name)
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private suspend fun downloadIDList(list: IDList, callCount: Long) {
        if (list.url == null) {
            return
        }
        var response: Response? = null
        val shouldLog = callCount % 50 == 1L
        val maybeDiagnostics = if (shouldLog) {
            diagnostics
        } else {
            null
        }
        val markerID = callCount.toString()
        try {
            maybeDiagnostics?.markStart(
                KeyType.GET_ID_LIST,
                StepType.NETWORK_REQUEST,
                additionalMarker = Marker(markerID = markerID),
            )
            response = transport.getExternal(list.url, mapOf("Range" to "bytes=${list.size}-"))
            maybeDiagnostics?.markEnd(
                KeyType.GET_ID_LIST,
                response?.isSuccessful === true,
                StepType.NETWORK_REQUEST,
                additionalMarker = Marker(
                    markerID = markerID,
                    statusCode = response?.code,
                    sdkRegion = response?.headers?.get("x-statsig-region"),
                ),
            )

            if (response?.isSuccessful !== true) {
                return
            }
            maybeDiagnostics?.markStart(
                KeyType.GET_ID_LIST,
                StepType.PROCESS,
                additionalMarker = Marker(markerID = markerID),
            )
            val contentLength = response.headers["content-length"]?.toIntOrNull()
            var content = response.body?.string()
            if (content == null || content.length <= 1) {
                return
            }
            val firstChar = content[0]
            if (contentLength == null || (firstChar != '-' && firstChar != '+')) {
                idLists.remove(list.name)
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
            maybeDiagnostics?.markEnd(
                KeyType.GET_ID_LIST,
                true,
                StepType.PROCESS,
                additionalMarker = Marker(markerID = markerID),
            )
        } catch (e: Exception) {
            errorBoundary.logException("downloadIDList", e)
            options.customLogger.error("An exception was caught when downloading ID lists:  $e")
            maybeDiagnostics?.markEnd(
                KeyType.GET_ID_LIST,
                false,
                StepType.NETWORK_REQUEST,
                additionalMarker = Marker(markerID = markerID),
            )
        } finally {
            response?.close()
        }
    }

    private suspend fun processDownloadedIDLists(idLists: Map<String, IDList>) {
        syncIdListsFromNetwork(idLists)
        diagnostics.clearContext(ContextType.CONFIG_SYNC)
    }

    private fun processDownloadedConfigs(configs: APIDownloadedConfigs?, source: DataSource): Boolean {
        try {
            if (configs == null) {
                if (source == DataSource.BOOTSTRAP) {
                    diagnostics.markEnd(KeyType.BOOTSTRAP, false, step = StepType.PROCESS)
                }
                return false
            }
            if (source == DataSource.DATA_STORE) {
                val updated = setDownloadedConfigs(configs)
                if (updated) {
                    this.evalReason = EvaluationReason.DATA_ADAPTER
                }
                return updated
            }
            if (source == DataSource.BOOTSTRAP) {
                this.evalReason = EvaluationReason.BOOTSTRAP
                setDownloadedConfigs(configs, true)
                diagnostics.markEnd(KeyType.BOOTSTRAP, true, step = StepType.PROCESS)
                return true
            }
            if (source == DataSource.NETWORK || source == DataSource.STATSIG_NETWORK) {
                // If Bootstrap and DataAdapter failed to load, defaulting to download config spec from network
                val updated = setDownloadedConfigs(configs)
                if (updated) {
                    this.evalReason = EvaluationReason.NETWORK
                    options.dataStore?.let {
                        downloadConfigSpecsToDataStore(it, configs)
                    }
                    fireRulesUpdatedCallback(configs)
                }
                return updated
            }
            return false
        } finally {
            diagnostics.clearContext(ContextType.CONFIG_SYNC)
        }
    }

    private suspend fun initializeSpecs(): FailureDetails? {
        var failureDetails: FailureDetails? = FailureDetails(FailureReason.EMPTY_SPEC)
        for (source in specUpdater.getInitializeOrder()) {
            val configs = specUpdater.getConfigSpecs(source)
            if (processDownloadedConfigs(configs.first, source)) {
                return null
            }
            failureDetails = configs.second
        }
        return failureDetails
    }

    private fun downloadConfigSpecsToDataStore(
        dataStore: IDataStore,
        response: APIDownloadedConfigs,
    ): APIDownloadedConfigs {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val specs: String = gson.toJson(response)

        val adapterKey = dataStore.dataStoreKey
        dataStore.set(adapterKey, specs)
        return response
    }

    private fun getParsedSpecs(values: Array<APIConfig>): Map<String, APIConfig> {
        val parsed: MutableMap<String, APIConfig> = emptyMap<String, APIConfig>().toMutableMap()
        var specName: String?
        for (value in values) {
            specName = value.name
            parsed[specName] = value
        }
        return parsed
    }
}
