package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import com.statsig.sdk.datastore.LocalFileDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.Response
import kotlin.collections.HashMap

const val STORAGE_ADAPTER_KEY = "statsig.cache"

internal class SpecStore constructor(
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val diagnostics: Diagnostics,
    private val serverSecret: String,
) {
    private var initTime: Long = 0
    private var initReason: EvaluationReason = EvaluationReason.UNINITIALIZED
    private var lastUpdateTime: Long = 0
    var isInitialized: Boolean = false
    var backgroundDownloadConfigs: Job? = null
    var backgroundDownloadIDLists: Job? = null

    private var dynamicConfigs: Map<String, APIConfig> = emptyMap()
    private var gates: Map<String, APIConfig> = emptyMap()
    private var layers: Map<String, Array<String>> = HashMap()
    private var idLists: MutableMap<String, IDList> = HashMap()
    private var sdkKeysToAppIDs: Map<String, String> = HashMap()
    private var hashedSDKKeysToAppIDs: Map<String, String> = HashMap()
    private var hashedSDKKeysToEntities: Map<String, APIEntityNames> = HashMap()

    private var layerConfigs: Map<String, APIConfig> = emptyMap()
    private var experimentToLayer: Map<String, String> = emptyMap()

    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    suspend fun initialize() {
        if (!options.localMode) {
            this.initializeSpecs()
            this.initTime = if (lastUpdateTime == 0L) -1 else lastUpdateTime

            this.spawnBackgroundThreadsIfNeeded()

            this.syncIdListsFromNetwork()
        }
        this.isInitialized = true
    }

    private fun spawnBackgroundThreadsIfNeeded() {
        if (this.backgroundDownloadIDLists?.isActive !== true) {
            this.spawnBackgroundDownloadIDLists()
        }

        if (this.backgroundDownloadConfigs?.isActive !== true) {
            this.spawnBackgroundDownloadConfigSpecs()
        }
    }

    fun shutdown() {
        if (this.options.localMode) {
            return
        }
        this.backgroundDownloadIDLists?.cancel()
        this.backgroundDownloadConfigs?.cancel()
    }

    private fun spawnBackgroundDownloadConfigSpecs() {
        backgroundDownloadConfigs =
            statsigScope.launch {
                pollForChanges().collect {
                    if (it == null) {
                        return@collect
                    }
                    setDownloadedConfigs(it)
                    if (it.hasUpdates) {
                        fireRulesUpdatedCallback(it)
                    }
                    diagnostics.clearContext(ContextType.CONFIG_SYNC)
                }
            }
    }

    private fun spawnBackgroundDownloadIDLists() {
        backgroundDownloadIDLists = statsigScope.launch {
            while (true) {
                delay(options.idListsSyncIntervalMs)
                syncIdListsFromNetwork()
                diagnostics.clearContext(ContextType.CONFIG_SYNC)
            }
        }
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
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
        }

        if (configString.isEmpty()) {
            return
        }

        options.rulesUpdatedCallback?.accept(configString)
    }

    private fun pollForChanges(): Flow<APIDownloadedConfigs?> {
        return flow {
            while (true) {
                delay(options.rulesetsSyncIntervalMs)
                val response = downloadConfigSpecs()
                if (response != null && response.hasUpdates) {
                    emit(response)
                }
            }
        }
    }

    private suspend fun syncIdListsFromNetwork() {
        var response: Response? = null
        try {
            val api = options.api ?: STATSIG_API_URL_BASE
            response = network.post(
                "$api/get_id_lists",
                mapOf("statsigMetadata" to statsigMetadata),
                emptyMap(),
                this.options.initTimeoutMs,
            ) ?: return
            if (!response.isSuccessful) {
                return
            }
            val body = response.body ?: return
            val jsonResponse = gson.fromJson<Map<String, IDList>>(body.string()) ?: return
            diagnostics.markStart(KeyType.GET_ID_LIST_SOURCES, StepType.PROCESS, additionalMarker = Marker(idListCount = jsonResponse.size))
            val tasks = mutableListOf<Job>()

            for ((name, serverList) in jsonResponse) {
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

                tasks.add(
                    statsigScope.launch {
                        downloadIDList(localList)
                    },
                )
            }

            tasks.joinAll()
            diagnostics.markEnd(KeyType.GET_ID_LIST_SOURCES, true, StepType.PROCESS)

            // remove deleted id lists
            val deletedLists = mutableListOf<String>()
            for (name in idLists.keys) {
                if (!jsonResponse.containsKey(name)) {
                    deletedLists.add(name)
                }
            }
            for (name in deletedLists) {
                idLists.remove(name)
            }
        } catch (e: Exception) {
            throw e
        } finally {
            response?.close()
        }
    }

    private suspend fun downloadIDList(list: IDList) {
        if (list.url == null) {
            return
        }
        var response: Response? = null
        try {
            diagnostics.markStart(KeyType.GET_ID_LIST, StepType.NETWORK_REQUEST, additionalMarker = Marker(url = list.url))
            response = network.getExternal(list.url, mapOf("Range" to "bytes=${list.size}-"))
            diagnostics.markEnd(KeyType.GET_ID_LIST, response?.isSuccessful === true, StepType.NETWORK_REQUEST, additionalMarker = Marker(url = list.url, statusCode = response?.code, sdkRegion = response?.headers?.get("x-statsig-region")))

            if (response?.isSuccessful !== true) {
                return
            }
            diagnostics.markStart(KeyType.GET_ID_LIST, StepType.PROCESS, additionalMarker = Marker(url = list.url))
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
            diagnostics.markEnd(KeyType.GET_ID_LIST, true, StepType.PROCESS, additionalMarker = Marker(url = list.url))
        } catch (e: Exception) {
            errorBoundary.logException("downloadIDList", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
            diagnostics.markEnd(KeyType.GET_ID_LIST, false, StepType.NETWORK_REQUEST, additionalMarker = Marker(url = list.url))
        } finally {
            response?.close()
        }
    }

    fun setDownloadedConfigs(downloadedConfig: APIDownloadedConfigs, isFromBootstrap: Boolean = false) {
        if (!downloadedConfig.hasUpdates) {
            return
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
        this.lastUpdateTime = downloadedConfig.time
        this.sdkKeysToAppIDs = downloadedConfig.sdkKeysToAppIDs ?: mapOf()
        this.hashedSDKKeysToAppIDs = downloadedConfig.hashedSDKKeysToAppIDs ?: mapOf()
        this.hashedSDKKeysToEntities = downloadedConfig.hashedSDKKeysToEntities ?: mapOf()

        if (downloadedConfig.diagnostics != null) {
            diagnostics.setSamplingRate(downloadedConfig.diagnostics)
        }
        if (options.dataStore == null && !isFromBootstrap) {
            diagnostics.markEnd(KeyType.DOWNLOAD_CONFIG_SPECS, true, StepType.PROCESS)
        }
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

    fun getAllIDLists(): Map<String, IDList> {
        return this.idLists
    }

    fun getInitTime(): Long {
        return this.initTime
    }
    fun getEvaluationReason(): EvaluationReason {
        return this.initReason
    }
    fun getLastUpdateTime(): Long {
        return this.lastUpdateTime
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

    private suspend fun initializeSpecs() {
        var downloadedConfigs: APIDownloadedConfigs? = null

        if (options.dataStore != null) {
            if (options.dataStore is LocalFileDataStore) {
                downloadedConfigs = this.downloadConfigSpecsToLocal()
            } else {
                downloadedConfigs = this.loadConfigSpecsFromStorageAdapter()
            }
            initReason =
                if (downloadedConfigs == null) EvaluationReason.UNINITIALIZED else EvaluationReason.DATA_ADAPTER
        } else if (options.bootstrapValues != null) {
            diagnostics.markStart(KeyType.BOOTSTRAP, step = StepType.PROCESS)
            downloadedConfigs = this.bootstrapConfigSpecs()
            initReason = if (downloadedConfigs == null) EvaluationReason.UNINITIALIZED else EvaluationReason.BOOTSTRAP
            if (downloadedConfigs != null) {
                setDownloadedConfigs(downloadedConfigs, true)
                diagnostics.markEnd(KeyType.BOOTSTRAP, true, step = StepType.PROCESS)
                return
            }
            diagnostics.markEnd(KeyType.BOOTSTRAP, false, step = StepType.PROCESS)
        }
        // If Bootstrap and DataAdapter failed to load, defaulting to download config spec from network
        if (initReason == EvaluationReason.UNINITIALIZED) {
            downloadedConfigs = this.downloadConfigSpecsFromNetwork()
            if (downloadedConfigs != null) {
                initReason = EvaluationReason.NETWORK
            }
        }
        if (downloadedConfigs != null) {
            if (options.bootstrapValues == null) {
                // only fire the callback if this was not the result of a bootstrap
                fireRulesUpdatedCallback(downloadedConfigs)
            }
            setDownloadedConfigs(downloadedConfigs)
        }
    }

    private suspend fun downloadConfigSpecsToLocal(): APIDownloadedConfigs? {
        val response = this.downloadConfigSpecs()

        val specs: String = gson.toJson(response)
        val localDataStore = options.dataStore as LocalFileDataStore
        localDataStore.set(localDataStore.filePath, specs)
        return response
    }

    private suspend fun downloadConfigSpecsFromNetwork(): APIDownloadedConfigs? {
        return this.downloadConfigSpecs()
    }

    private fun getParsedSpecs(values: Array<APIConfig>): Map<String, APIConfig> {
        var parsed: MutableMap<String, APIConfig> = emptyMap<String, APIConfig>().toMutableMap()
        var specName: String?
        for (value in values) {
            specName = value.name
            parsed[specName] = value
        }
        return parsed
    }

    private fun bootstrapConfigSpecs(): APIDownloadedConfigs? {
        try {
            val specs = this.parseConfigSpecs(this.options.bootstrapValues)
            if (specs === null) {
                return null
            }
            return specs
        } catch (e: Exception) {
            throw Exception("Failed to parse bootstrap values")
        }
    }

    private fun loadConfigSpecsFromStorageAdapter(): APIDownloadedConfigs? {
        if (options.dataStore == null) {
            return null
        }
        val cacheString = options.dataStore!!.get(STORAGE_ADAPTER_KEY)

        val specs = parseConfigSpecs(cacheString)
        if (specs != null) {
            if (specs.time < this.lastUpdateTime) {
                return null
            }
            this.lastUpdateTime = specs.time
        }
        return specs
    }

    private fun parseConfigSpecs(specs: String?): APIDownloadedConfigs? {
        if (specs.isNullOrEmpty()) {
            return null
        }
        try {
            return gson.fromJson(specs, APIDownloadedConfigs::class.java)
        } catch (e: Exception) {
            errorBoundary.logException("parseConfigSpecs", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
        }
        return null
    }

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs? {
        var response: Response? = null
        try {
            response = this.network.downloadConfigSpecs(this.lastUpdateTime, this.options.initTimeoutMs) ?: return null
            val configs = gson.fromJson(response.body?.charStream(), APIDownloadedConfigs::class.java)
            if (configs.hashedSDKKeyUsed != null && configs.hashedSDKKeyUsed != Hashing.djb2(serverSecret)) {
                return null
            }
            if (configs.hasUpdates) {
                this.lastUpdateTime = configs.time
            }
            return configs
        } catch (e: Exception) {
            errorBoundary.logException("downloadConfigSpecs", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
        } finally {
            response?.close()
        }
        return null
    }
}
