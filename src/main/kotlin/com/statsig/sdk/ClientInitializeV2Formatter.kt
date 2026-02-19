package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class ClientInitializeV2Response(
    @SerializedName("feature_gates") var featureGates: Map<String, ClientInitV2Gate>,
    @SerializedName("dynamic_configs") var dynamicConfigs: Map<String, ClientInitV2Config>,
    @SerializedName("layer_configs") var layerConfigs: Map<String, ClientInitV2Layer>,
    @SerializedName("has_updates") var hasUpdates: Boolean,
    @SerializedName("time") var time: Long,
    @SerializedName("hash_used") var hashUsed: String,
    @SerializedName("user") var user: Map<String, Any?>,
    @SerializedName("sdk_info") var sdkInfo: Map<String, String>,
    @SerializedName("exposures") var exposures: Map<String, Map<String, String>>,
    @SerializedName("values") var values: Map<String, Any>,
) {

    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["feature_gates"] = featureGates.mapValues { (_, config) -> config.toMap() }
        map["dynamic_configs"] = dynamicConfigs.mapValues { (_, config) -> config.toMap() }
        map["layer_configs"] = layerConfigs.mapValues { (_, config) -> config.toMap() }
        map["has_updates"] = hasUpdates
        map["time"] = time
        map["hash_used"] = hashUsed
        map["user"] = user.toMap()
        map["sdk_info"] = sdkInfo
        map["exposures"] = exposures
        map["values"] = values
        return map
    }

    fun isEmpty(): Boolean {
        return featureGates.isEmpty() && dynamicConfigs.isEmpty() && layerConfigs.isEmpty()
    }
}

internal data class ClientInitV2Gate(
    @SerializedName("n") var name: String,
    @SerializedName("v") var value: Boolean,
    @SerializedName("r") var ruleID: String,
    @SerializedName("s") var secondaryExposures: ArrayList<String>,
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        if (value == true) {
            map["v"] = value
        }
        if (ruleID != "default") {
            map["r"] = ruleID
        }
        if (!secondaryExposures.isEmpty()) {
            map["s"] = secondaryExposures
        }
        return map
    }
}

internal data class ClientInitV2Config(
    @SerializedName("n") var name: String,
    @SerializedName("v") var value: String,
    @SerializedName("r") var ruleID: String,
    @SerializedName("s") var secondaryExposures: ArrayList<String>,
    @SerializedName("ue") var isUserInExperiment: Boolean? = null,
    @SerializedName("ea") var isExperimentActive: Boolean? = null,
    @SerializedName("gn") var groupName: String? = null,
    @SerializedName("p") var passed: Boolean? = null,
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        map["v"] = value
        if (ruleID != "default") {
            map["r"] = ruleID
        }
        if (!secondaryExposures.isEmpty()) {
            map["s"] = secondaryExposures
        }
        if (isUserInExperiment != null && isUserInExperiment == true) {
            map["ue"] = isUserInExperiment
        }
        if (isExperimentActive != null && isExperimentActive == true) {
            map["ea"] = isExperimentActive
        }
        if (groupName != null) {
            map["gn"] = groupName
        }
        if (passed != null && passed == true) {
            map["p"] = passed
        }
        return map
    }
}

internal data class ClientInitV2Layer(
    @SerializedName("n") var name: String,
    @SerializedName("v") var value: String,
    @SerializedName("r") var ruleID: String,
    @SerializedName("s") var secondaryExposures: ArrayList<String>,
    @SerializedName("us")
    var undelegatedSecondaryExposures: ArrayList<String> = arrayListOf(),
    @SerializedName("ue") var isUserInExperiment: Boolean? = null,
    @SerializedName("ea") var isExperimentActive: Boolean? = null,
    @SerializedName("gn") var groupName: String? = null,
    @SerializedName("ep") var explicitParameters: Array<String>? = null,
    @SerializedName("ae") var allocatedExperimentName: String? = null,
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        map["v"] = value
        if (ruleID != "default") {
            map["r"] = ruleID
        }
        if (!secondaryExposures.isEmpty()) {
            map["s"] = secondaryExposures
        }
        if (!undelegatedSecondaryExposures.isEmpty()) {
            map["us"] = undelegatedSecondaryExposures
        }
        if (isUserInExperiment != null && isUserInExperiment == true) {
            map["ue"] = isUserInExperiment
        }
        if (isExperimentActive != null && isExperimentActive == true) {
            map["ea"] = isExperimentActive
        }
        if (groupName != null) {
            map["gn"] = groupName
        }
        if (explicitParameters != null) {
            map["ep"] = explicitParameters
        }
        if (allocatedExperimentName != null) {
            map["ae"] = allocatedExperimentName
        }
        return map
    }
}

internal class ClientInitializeV2Formatter(
    private val specStore: SpecStore,
    private val evalFun: (ctx: EvaluationContext, config: APIConfig) -> Unit,
    private val context: EvaluationContext,
) {
    private val user: StatsigUser = context.user
    private val clientSDKKey: String? = context.clientSDKKey
    private val hash: HashAlgo = context.hash
    private val exposures: MutableMap<String, Map<String, String>> = mutableMapOf()
    private val values: MutableMap<String, Any> = mutableMapOf()

    fun getFormattedResponse(): ClientInitializeV2Response {
        val evaluatedKeys = mutableMapOf<String, Any>()
        user.userID?.let { userId ->
            evaluatedKeys["userID"] = userId
        }

        user.customIDs?.let { customIds ->
            if (customIds.keys.isNotEmpty()) {
                evaluatedKeys["customIDs"] = customIds
            }
        }

        fun filterNullsConfig(arr: List<ClientInitV2Config?>): Map<String, ClientInitV2Config> {
            val res: MutableMap<String, ClientInitV2Config> = mutableMapOf()
            for (el in arr) {
                if (el == null) {
                    continue
                }
                res[el.name] = el
            }
            return res
        }

        fun filterNullsGate(arr: List<ClientInitV2Gate?>): Map<String, ClientInitV2Gate> {
            val res: MutableMap<String, ClientInitV2Gate> = mutableMapOf()
            for (el in arr) {
                if (el == null) {
                    continue
                }
                res[el.name] = el
            }
            return res
        }

        fun filterNullsLayer(arr: List<ClientInitV2Layer?>): Map<String, ClientInitV2Layer> {
            val res: MutableMap<String, ClientInitV2Layer> = mutableMapOf()
            for (el in arr) {
                if (el == null) {
                    continue
                }
                res[el.name] = el
            }
            return res
        }

        fun mapGatesFn(configs: Map<String, APIConfig>): Map<String, ClientInitV2Gate> {
            val res = configs.map { entry ->
                gateToResponse(entry.key, entry.value)
            }
            return filterNullsGate(res)
        }

        fun mapConfigFn(configs: Map<String, APIConfig>): Map<String, ClientInitV2Config> {
            val res = configs.map { entry ->
                configToResponse(entry.key, entry.value)
            }
            return filterNullsConfig(res)
        }

        fun mapLayerFn(configs: Map<String, APIConfig>): Map<String, ClientInitV2Layer> {
            val res = configs.map { entry ->
                layerToResponse(entry.key, entry.value)
            }
            return filterNullsLayer(res)
        }
        var gates = specStore.getAllGates()
        var configs = specStore.getAllConfigs()
        if (clientSDKKey != null) {
            val entities = specStore.getEntitiesFromKey(clientSDKKey)
            if (entities != null) {
                gates = gates.filter { entities.gates.contains(it.key) }
                configs = configs.filter { entities.configs.contains(it.key) }
            }
        }

        val metadata = StatsigMetadata()

        return ClientInitializeV2Response(
            mapGatesFn(gates),
            mapConfigFn(configs),
            mapLayerFn(specStore.getAllLayerConfigs()),
            true, // has_updates
            specStore.getLastUpdateTime(),
            this.hash.toString().lowercase(),
            user.toMapForLogging(),
            mutableMapOf<String, String>().apply {
                this["sdkType"] = metadata.sdkType
                this["sdkVersion"] = metadata.sdkVersion
            },
            exposures,
            values,
        )
    }

    private fun dedupeExposures(secondaryExposures: List<Map<String, String>>): ArrayList<String> {
        val exposureKeys = ArrayList<String>()
        for (exposure in secondaryExposures) {
            val key = exposure["gate"] ?: "" + ":" + exposure["gateValue"] ?: "" + ":" + exposure["ruleID"] ?: ""
            val hashKey = Hashing.djb2(key)
            if (this.exposures.containsKey(hashKey)) {
                exposureKeys.add(hashKey)
            } else {
                this.exposures[hashKey] = exposure
                exposureKeys.add(hashKey)
            }
        }
        return exposureKeys
    }

    private fun dedupeValue(value: Any): String {
        val valueStr = value.toString()
        val hashValue = Hashing.djb2(valueStr)
        if (!this.values.containsKey(hashValue)) {
            this.values[hashValue] = value
        }
        return hashValue
    }

    private fun populateLayerFields(configSpec: APIConfig, evalResult: ConfigEvaluation, result: ClientInitV2Layer) {
        val delegate = evalResult.configDelegate
        result.explicitParameters = configSpec.explicitParameters ?: emptyArray()

        if (delegate != null && delegate != "") {
            val delegateSpec = specStore.getConfig(delegate)
            var delegateContext = context.asNewEvaluation()
            if (delegateSpec != null) {
                evalFun(delegateContext, delegateSpec)
                result.allocatedExperimentName = hashName(delegate)
                result.isUserInExperiment = delegateContext.evaluation.isExperimentGroup
                result.isExperimentActive = delegateSpec.isActive
                result.explicitParameters = delegateSpec.explicitParameters ?: emptyArray()
                result.secondaryExposures = dedupeExposures(hashExposures(delegateContext.evaluation.secondaryExposures))
                if (delegateContext.evaluation.groupName != null && delegateContext.evaluation.groupName != "") {
                    result.groupName = delegateContext.evaluation.groupName
                }
            } else {
                delegateContext.evaluation = ConfigEvaluation(
                    evaluationDetails = EvaluationDetails(
                        this.specStore.getLastUpdateTime(),
                        this.specStore.getInitTime(),
                        EvaluationReason.UNRECOGNIZED,
                    ),
                )
            }
        }

        result.undelegatedSecondaryExposures = dedupeExposures(hashExposures(evalResult.undelegatedSecondaryExposures))
    }

    private fun hashExposures(exposures: ArrayList<Map<String, String>>): ArrayList<Map<String, String>> {
        val hashedExposures = ArrayList<Map<String, String>>()

        for (exposure in exposures) {
            val hashedExposure = mapOf(
                "gate" to hashName(exposure["gate"] ?: ""),
                "gateValue" to (exposure["gateValue"] ?: ""),
                "ruleID" to (exposure["ruleID"] ?: ""),
            )
            hashedExposures.add(hashedExposure)
        }

        return hashedExposures
    }

    private fun gateToResponse(gateName: String, configSpec: APIConfig): ClientInitV2Gate? {
        if (configSpec.entity == "segment" || configSpec.entity == "holdout") {
            return null
        }

        if (!configSpecIsForThisTargetApp(configSpec)) {
            return null
        }

        val evalContext = context.asNewEvaluation()
        evalFun(evalContext, configSpec)
        val hashedName = hashName(gateName)

        val hashedExposures = hashExposures(evalContext.evaluation.secondaryExposures)

        val result = ClientInitV2Gate(
            hashedName,
            evalContext.evaluation.booleanValue,
            evalContext.evaluation.ruleID,
            dedupeExposures(hashedExposures),
        )
        return result
    }

    private fun configToResponse(configName: String, configSpec: APIConfig): ClientInitV2Config? {
        if (!configSpecIsForThisTargetApp(configSpec)) {
            return null
        }

        val evalContext = context.asNewEvaluation()
        evalFun(evalContext, configSpec)
        val hashedName = hashName(configName)

        val hashedExposures = hashExposures(evalContext.evaluation.secondaryExposures)

        val result = ClientInitV2Config(
            hashedName,
            dedupeValue(evalContext.evaluation.jsonValue ?: emptyMap<Any, Any>()),
            evalContext.evaluation.ruleID,
            dedupeExposures(hashedExposures),
        )
        val category = configSpec.type
        val entityType = configSpec.entity

        if (entityType == "dynamic_config") {
            result.passed = evalContext.evaluation.booleanValue
        }

        if (evalContext.evaluation.groupName != null && evalContext.evaluation.groupName != "") {
            result.groupName = evalContext.evaluation.groupName
        }

        if (entityType == "experiment") {
            if (evalContext.evaluation.isExperimentGroup == true) {
                result.isUserInExperiment = evalContext.evaluation.isExperimentGroup
            }

            if (configSpec.isActive) {
                result.isExperimentActive = configSpec.isActive
            }
        }
        return result
    }

    private fun layerToResponse(configName: String, configSpec: APIConfig): ClientInitV2Layer? {
        if (!configSpecIsForThisTargetApp(configSpec)) {
            return null
        }

        val evalContext = context.asNewEvaluation()
        evalFun(evalContext, configSpec)
        val hashedName = hashName(configName)

        val hashedExposures = hashExposures(evalContext.evaluation.secondaryExposures)

        val result = ClientInitV2Layer(
            hashedName,
            dedupeValue(evalContext.evaluation.jsonValue ?: emptyMap<Any, Any>()),
            evalContext.evaluation.ruleID,
            dedupeExposures(hashedExposures),
        )

        if (evalContext.evaluation.groupName != null && evalContext.evaluation.groupName != "") {
            result.groupName = evalContext.evaluation.groupName
        }

        populateLayerFields(configSpec, evalContext.evaluation, result)
        return result
    }

    private fun hashName(name: String): String {
        return when (this.hash) {
            HashAlgo.NONE -> name
            HashAlgo.DJB2 -> Hashing.djb2(name)
            else -> Hashing.sha256(name)
        }
    }

    private fun configSpecIsForThisTargetApp(configSpec: APIConfig): Boolean {
        if (clientSDKKey == null) {
            // no client key provided, send me everything
            return true
        }
        var targetAppID = specStore.getAppIDFromKey(clientSDKKey)
        if (targetAppID == null) {
            // no target app id for the given SDK key, send me everything
            return true
        }
        if (configSpec.targetAppIDs == null) {
            // no target app id associated with this config
            // if the key does have a target app id its not for this app
            return false
        }

        return configSpec.targetAppIDs.contains(targetAppID)
    }
}
