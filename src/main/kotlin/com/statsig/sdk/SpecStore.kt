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

internal class SpecStore constructor(
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private var statsigMetadata: StatsigMetadata,
    private var statsigScope: CoroutineScope,
) {
    private var lastUpdateTime: Long = 0
    private var lastSyncTime: Long = 0
    var isInitialized: Boolean = false
    var backgroundDownloadConfigs: Job? = null
    var backgroundDownloadIDLists: Job? = null

    private var dynamicConfigs: Map<String, APIConfig> = emptyMap()
    private var gates: Map<String, APIConfig> = emptyMap()
    private var layers: Map<String, Array<String>> = HashMap()
    private var idLists: MutableMap<String, IDList> = HashMap()

    private var layerConfigs: Map<String, APIConfig> = emptyMap()
    private var experimentToLayer: Map<String, String> = emptyMap()

    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private inline fun <reified T> Gson.fromJson(json: String) = fromJson<T>(json, object : TypeToken<T>() {}.type)

    suspend fun initialize() {
        if (!options.localMode) {
            this.initializeSpecs()

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
        try {
            val configString = configSpecs.toString()
            options.rulesUpdatedCallback?.accept(configString)
        } catch (e: Exception) {
        }
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
            options.api + "/get_id_lists", mapOf("statsigMetadata" to statsigMetadata), emptyMap()
        ) ?: return
        if (!response.isSuccessful) {
            return
        }
        val body = response.body ?: return
        val jsonResponse = gson.fromJson<Map<String, IDList>>(body.string()) ?: return
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
                    creationTime = serverList.creationTime
                )
                idLists[name] = localList
            }
            if (serverList.size <= localList.size) {
                continue
            }

            tasks.add(
                statsigScope.launch {
                    downloadIDList(localList)
                }
            )
        }

        tasks.joinAll()

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
            val response = network.post(list.url!!, null, mapOf("Range" to "bytes=${list.size}-"))
            if (response?.isSuccessful !== true) {
                return
            }
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
        } catch (_: Exception) {
        }
    }

    fun setDownloadedConfigs(downloadedConfig: APIDownloadedConfigs) {
        if (!downloadedConfig.hasUpdates) {
            return
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

    private suspend fun initializeSpecs() {
        val downloadedConfigs = if (options.bootstrapValues != null) {
            this.bootstrapConfigSpecs()
        } else {
            this.downloadConfigSpecs()
        }
        if (downloadedConfigs != null) {
            if (options.bootstrapValues == null) {
                // only fire the callback if this was not the result of a bootstrap
                fireRulesUpdatedCallback(downloadedConfigs)
            }
            setDownloadedConfigs(downloadedConfigs)
        }
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
            throw Exception("Failed to parse bootstrapvalues")
        }
    }

    private fun parseConfigSpecs(specs: String?): APIDownloadedConfigs? {
        if (specs.isNullOrEmpty()) {
            return null
        }
        try {
            return gson.fromJson(specs, APIDownloadedConfigs::class.java)
        } catch (_: Exception) {
        }
        return null
    }

    suspend fun downloadConfigSpecs(): APIDownloadedConfigs? {
        try {
            val specs = this.network.post(
                options.api + "/download_config_specs",
                mapOf("statsigMetadata" to statsigMetadata, "sinceTime" to this.lastSyncTime),
                emptyMap()
            ) ?: return null
            val configs = gson.fromJson(specs.body?.charStream(), APIDownloadedConfigs::class.java)
            if (configs.hasUpdates) {
                this.lastSyncTime = configs.time
            }
            return configs
        } catch (e: Exception) {
        }
        return null
    }
}
