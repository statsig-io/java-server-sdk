package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.collections.HashMap

const val STORAGE_ADAPTER_KEY = "statsig.cache"

internal class SpecStore constructor(
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val diagnostics: Diagnostics,
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
                }
            }
    }

    private fun spawnBackgroundDownloadIDLists() {
        backgroundDownloadIDLists = statsigScope.launch {
            while (true) {
                delay(options.idListsSyncIntervalMs)
                syncIdListsFromNetwork()
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
            println("[Statsig]: An exception was caught:  $e")
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
        val response = network.post(
            options.api + "/get_id_lists",
            mapOf("statsigMetadata" to statsigMetadata),
            emptyMap(),
            this.options.initTimeoutMs,
        ) ?: return
        if (!response.isSuccessful) {
            return
        }
        val body = response.body ?: return
        val jsonResponse = gson.fromJson<Map<String, IDList>>(body.string()) ?: return
        diagnostics.markStart(KeyType.GET_ID_LIST_SOURCES, StepType.PROCESS, Marker(idListCount = jsonResponse.size))
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
    }

    private suspend fun downloadIDList(list: IDList) {
        if (list.url == null) {
            return
        }

        try {
            diagnostics.markStart(KeyType.GET_ID_LIST, StepType.NETWORK_REQUEST, additionalMarker = Marker(url = list.url))
            val response = network.postExternal(list.url!!, null, mapOf("Range" to "bytes=${list.size}-"))
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
            println("[Statsig]: An exception was caught:  $e")
            diagnostics.markEnd(KeyType.GET_ID_LIST, false, StepType.NETWORK_REQUEST, additionalMarker = Marker(url = list.url))
        }
    }

    fun setDownloadedConfigs(downloadedConfig: APIDownloadedConfigs) {
        if (!downloadedConfig.hasUpdates) {
            return
        }
        val diagnosticKey = if (options.bootstrapValues == null) KeyType.DOWNLOAD_CONFIG_SPECS else KeyType.BOOTSTRAP
        diagnostics.markStart(diagnosticKey, step = StepType.PROCESS)
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
        if (downloadedConfig.sdkKeysToAppIDs != null) {
            this.sdkKeysToAppIDs = downloadedConfig.sdkKeysToAppIDs
        }
        diagnostics.markEnd(diagnosticKey, true, step = StepType.PROCESS)
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
        return this.sdkKeysToAppIDs.get(clientSDKKey)
    }

    private suspend fun initializeSpecs() {
        var downloadedConfigs: APIDownloadedConfigs?

        if (options.dataStore != null) {
            downloadedConfigs = this.loadConfigSpecsFromStorageAdapter()
            initReason = EvaluationReason.DATA_ADAPTER

            if (this.lastUpdateTime == 0L) {
                downloadedConfigs = this.downloadConfigSpecsFromNetwork()
            }
        } else if (options.bootstrapValues != null) {
            downloadedConfigs = this.bootstrapConfigSpecs()
            initReason = EvaluationReason.BOOTSTRAP
        } else {
            downloadedConfigs = this.downloadConfigSpecsFromNetwork()
        }
        if (downloadedConfigs != null) {
            if (options.bootstrapValues == null) {
                // only fire the callback if this was not the result of a bootstrap
                fireRulesUpdatedCallback(downloadedConfigs)
            }
            setDownloadedConfigs(downloadedConfigs)
        }
    }

    private suspend fun downloadConfigSpecsFromNetwork(): APIDownloadedConfigs? {
        this.initReason = EvaluationReason.NETWORK
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
            println("[Statsig]: An exception was caught:  $e")
        }
        return null
    }

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs? {
        try {
            val specs = this.network.post(
                options.api + "/download_config_specs",
                mapOf("statsigMetadata" to statsigMetadata, "sinceTime" to this.lastUpdateTime),
                emptyMap(),
                this.options.initTimeoutMs,
            ) ?: return null
            val configs = gson.fromJson(specs.body?.charStream(), APIDownloadedConfigs::class.java)
            if (configs.hasUpdates) {
                this.lastUpdateTime = configs.time
            }
            return configs
        } catch (e: Exception) {
            errorBoundary.logException("downloadConfigSpecs", e)
            println("[Statsig]: An exception was caught:  $e")
        }
        return null
    }
}
