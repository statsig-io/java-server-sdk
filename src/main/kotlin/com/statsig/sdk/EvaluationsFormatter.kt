package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class EvaluationsResponse(
    @SerializedName("feature_gates") var featureGates: Map<String, EvaluationClientConfig>,
    @SerializedName("dynamic_configs") var dynamicConfigs: Map<String, EvaluationClientConfig>,
    @SerializedName("layer_configs") var layerConfigs: Map<String, EvaluationClientConfig>,
    @SerializedName("has_updates") var hasUpdates: Boolean,
    @SerializedName("time") var time: Long,
    @SerializedName("hash_used") var hashUsed: String,
    @SerializedName("user") var user: Map<String, Any?>,
    @SerializedName("sdk_info") var sdkInfo: Map<String, String>,
    @SerializedName("exposures") var exposures: ArrayList<Map<String, String>>,
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
        return map
    }

    fun isEmpty(): Boolean {
        return featureGates.isEmpty() && dynamicConfigs.isEmpty() && layerConfigs.isEmpty()
    }
}

internal data class EvaluationClientConfig(
    @SerializedName("name") var name: String,
    @SerializedName("value") var value: Any,
    @SerializedName("rule_id") var ruleID: String,
    @SerializedName("secondary_exposures") var secondaryExposures: ArrayList<Int>,
    @SerializedName("undelegated_secondary_exposures")
    var undelegatedSecondaryExposures: ArrayList<Int>? = null,
    @SerializedName("allocated_experiment_name") var allocatedExperimentName: String? = null,
    @SerializedName("is_user_in_experiment") var isUserInExperiment: Boolean? = null,
    @SerializedName("is_experiment_active") var isExperimentActive: Boolean? = null,
    @SerializedName("explicit_parameters") var explicitParameters: Array<String>? = null,
    @SerializedName("is_in_layer") var isInLayer: Boolean? = null,
    @SerializedName("group_name") var groupName: String? = null,
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        map["value"] = value
        map["rule_id"] = ruleID
        if (!secondaryExposures.isEmpty()) {
            map["secondary_exposures"] = secondaryExposures
        }
        if (undelegatedSecondaryExposures != null) {
            map["undelegated_secondary_exposures"] =
                undelegatedSecondaryExposures
        }
        if (allocatedExperimentName != null) {
            map["allocated_experiment_name"] = allocatedExperimentName
        }
        if (isUserInExperiment != null) {
            map["is_user_in_experiment"] = isUserInExperiment
        }
        if (isExperimentActive != null) {
            map["is_experiment_active"] = isExperimentActive
        }
        if (explicitParameters != null) {
            map["explicit_parameters"] = explicitParameters
        }
        if (isInLayer != null) {
            map["is_in_layer"] = isInLayer
        }
        if (groupName != null) {
            map["group_name"] = groupName
        }
        return map
    }
}

internal class EvaluationsFormatter(
    private val specStore: SpecStore,
    private val evalFun: (ctx: EvaluationContext, config: APIConfig) -> Unit,
    private val context: EvaluationContext,
) {
    private val user: StatsigUser = context.user
    private val clientSDKKey: String? = context.clientSDKKey
    private val hash: HashAlgo = context.hash
    private val exposures: ArrayList<Map<String, String>> = ArrayList<Map<String, String>>()
    private val exposureMap: MutableMap<String, Int> = mutableMapOf()

    fun getFormattedResponse(): EvaluationsResponse {
        val evaluatedKeys = mutableMapOf<String, Any>()
        user.userID?.let { userId ->
            evaluatedKeys["userID"] = userId
        }

        user.customIDs?.let { customIds ->
            if (customIds.keys.isNotEmpty()) {
                evaluatedKeys["customIDs"] = customIds
            }
        }

        fun filterNulls(arr: List<EvaluationClientConfig?>): Map<String, EvaluationClientConfig> {
            val res: MutableMap<String, EvaluationClientConfig> = mutableMapOf()
            for (el in arr) {
                if (el == null) {
                    continue
                }
                res[el.name] = el
            }
            return res
        }

        fun mapFn(configs: Map<String, APIConfig>): Map<String, EvaluationClientConfig> {
            val res = configs.map { entry ->
                configToResponse(entry.key, entry.value)
            }
            return filterNulls(res)
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

        return EvaluationsResponse(
            mapFn(gates),
            mapFn(configs),
            mapFn(specStore.getAllLayerConfigs()),
            true, // has_updates
            specStore.getLastUpdateTime(),
            this.hash.toString().lowercase(),
            user.toMapForLogging(),
            mutableMapOf<String, String>().apply {
                this["sdkType"] = metadata.sdkType
                this["sdkVersion"] = metadata.sdkVersion
            },
            exposures,
        )
    }

    private fun dedupeExposures(exposures: List<Map<String, String>>): ArrayList<Int> {
        val compressedExposures = ArrayList<Int>()
        for (exposure in exposures) {
            val key = exposure["gate"] ?: "" + ":" + exposure["gateValue"] ?: "" + ":" + exposure["ruleID"] ?: ""
            val exposureIndex = exposureMap.getOrDefault(key, -1)
            if (exposureIndex == -1) {
                exposureMap[key] = exposureMap.size
                this.exposures.add(exposure)
                compressedExposures.add(exposureMap.size - 1)
            } else {
                compressedExposures.add(exposureIndex)
            }
        }
        return compressedExposures
    }

    private fun populateExperimentFields(
        configName: String,
        configSpec: APIConfig,
        evalResult: ConfigEvaluation,
        result: EvaluationClientConfig,
    ) {
        if (evalResult.isExperimentGroup == true) {
            result.isUserInExperiment = evalResult.isExperimentGroup
        }

        if (configSpec.isActive) {
            result.isExperimentActive = configSpec.isActive
        }

        if (configSpec.hasSharedParams != true) {
            return
        }

        result.isInLayer = true
        result.explicitParameters = configSpec.explicitParameters ?: emptyArray()

        val layerName = specStore.getLayerNameForExperiment(configName) ?: return
        val layer = specStore.getLayerConfig(layerName) ?: return

        val layerValue = layer.defaultValue as Map<String, Any>
        val currentValue = result.value as Map<String, Any>
        result.value = layerValue + currentValue
    }

    private fun populateLayerFields(configSpec: APIConfig, evalResult: ConfigEvaluation, result: EvaluationClientConfig) {
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
                        EvaluationReason.UNRECOGNIZED
                    )
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

    private fun configToResponse(configName: String, configSpec: APIConfig): EvaluationClientConfig? {
        if (configSpec.entity == "segment" || configSpec.entity == "holdout") {
            return null
        }

        if (!configSpecIsForThisTargetApp(configSpec)) {
            return null
        }

        val evalContext = context.asNewEvaluation()
        evalFun(evalContext, configSpec)
        val hashedName = hashName(configName)

        val hashedExposures = hashExposures(evalContext.evaluation.secondaryExposures)

        val result = EvaluationClientConfig(
            hashedName,
            "value" to false,
            evalContext.evaluation.ruleID,
            dedupeExposures(hashedExposures)
        )
        val category = configSpec.type
        val entityType = configSpec.entity
        if (category == "feature_gate") {
            result.value = evalContext.evaluation.booleanValue
            return result
        } else if (category == "dynamic_config") {
            result.value = evalContext.evaluation.jsonValue ?: emptyMap<Any, Any>()
            if (evalContext.evaluation.groupName != null && evalContext.evaluation.groupName != "") {
                result.groupName = evalContext.evaluation.groupName
            }

            if (entityType == "experiment") {
                populateExperimentFields(
                    configName,
                    configSpec,
                    evalContext.evaluation,
                    result,
                )
            } else if (entityType == "layer") {
                populateLayerFields(configSpec, evalContext.evaluation, result)
            }
            return result
        }
        return null
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
