package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class ClientInitializeResponse(
    @SerializedName("feature_gates") var feature_gates: Map<String, ClientConfig>,
    @SerializedName("dynamic_configs") var dynamic_configs: Map<String, ClientConfig>,
    @SerializedName("layer_configs") var layer_configs: Map<String, ClientConfig>,
    @SerializedName("sdkParams") var sdkParams: Map<String, Any>,
    @SerializedName("has_updates") var has_updates: Boolean,
    @SerializedName("time") var time: Long,
    @SerializedName("evaluated_keys") var evaluated_keys: Map<String, Any>,
    @SerializedName("hash_used") var hash_used: String,
    @SerializedName("user") var user: StatsigUser,
    @SerializedName("sdkInfo") var sdkInfo: Map<String, String>,
) {

    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["feature_gates"] = feature_gates.mapValues { (_, config) -> config.toMap() }
        map["dynamic_configs"] = dynamic_configs.mapValues { (_, config) -> config.toMap() }
        map["layer_configs"] = layer_configs.mapValues { (_, config) -> config.toMap() }
        map["sdkParams"] = sdkParams
        map["has_updates"] = has_updates
        map["time"] = time
        map["evaluated_keys"] = evaluated_keys
        map["hash_used"] = hash_used
        map["user"] = user
        map["sdkInfo"] = sdkInfo
        return map
    }

    fun isEmpty(): Boolean {
        return feature_gates.isEmpty() && dynamic_configs.isEmpty() && layer_configs.isEmpty()
    }
}

internal data class ClientConfig(
    @SerializedName("name") var name: String,
    @SerializedName("value") var value: Any,
    @SerializedName("rule_id") var ruleID: String,
    @SerializedName("secondary_exposures") var secondaryExposures: ArrayList<Map<String, String>>,
    @SerializedName("undelegated_secondary_exposures")
    var undelegatedSecondaryExposures: ArrayList<Map<String, String>>? = null,
    @SerializedName("group") var group: String? = null,
    @SerializedName("allocated_experiment_name") var allocatedExperimentName: String? = null,
    @SerializedName("is_user_in_experiment") var isUserInExperiment: Boolean? = null,
    @SerializedName("is_experiment_active") var isExperimentActive: Boolean? = null,
    @SerializedName("explicit_parameters") var explicitParameters: Array<String>? = null,
    @SerializedName("is_in_layer") var isInLayer: Boolean? = null,
    @SerializedName("is_device_based") var isDeviceBased: Boolean? = null,
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        map["name"] = name
        map["value"] = value
        map["rule_id"] = ruleID
        map["secondary_exposures"] = secondaryExposures
        if (undelegatedSecondaryExposures != null) map["undelegated_secondary_exposures"] =
            undelegatedSecondaryExposures
        if (group != null) map["group"] = group
        if (allocatedExperimentName != null) map["allocated_experiment_name"] = allocatedExperimentName
        if (isUserInExperiment != null) map["is_user_in_experiment"] = isUserInExperiment
        if (isExperimentActive != null) map["is_experiment_active"] = isExperimentActive
        if (explicitParameters != null) map["explicit_parameters"] = explicitParameters
        if (isInLayer != null) map["is_in_layer"] = isInLayer
        if (isDeviceBased != null) map["is_device_based"] = isDeviceBased
        return map
    }
}

internal class ClientInitializeFormatter(
    private val specStore: SpecStore,
    private val evalFun: (user: StatsigUser, config: APIConfig?, endResult: ConfigEvaluation) -> Unit,
    private val user: StatsigUser,
    private val hash: HashAlgo = HashAlgo.SHA256,
    private val clientSDKKey: String? = null,
) {

    fun getFormattedResponse(): ClientInitializeResponse {
        val evaluatedKeys = mutableMapOf<String, Any>()
        user.userID?.let { userId ->
            evaluatedKeys["userID"] = userId
        }

        user.customIDs?.let { customIds ->
            if (customIds.keys.isNotEmpty()) {
                evaluatedKeys["customIDs"] = customIds
            }
        }

        fun filterNulls(arr: List<ClientConfig?>): Map<String, ClientConfig> {
            val res: MutableMap<String, ClientConfig> = mutableMapOf()
            for (el in arr) {
                if (el == null) continue
                res[el.name] = el
            }
            return res
        }

        fun mapFn(configs: Map<String, APIConfig>): Map<String, ClientConfig> {
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

        return ClientInitializeResponse(
            mapFn(gates),
            mapFn(configs),
            mapFn(specStore.getAllLayerConfigs()),
            emptyMap(),
            true,
            specStore.getLastUpdateTime(),
            evaluatedKeys,
            this.hash.toString().lowercase(),
            user,
            mutableMapOf<String, String>().apply {
                this["sdkType"] = metadata.sdkType
                this["sdkVersion"] = metadata.sdkVersion
            }
        )
    }

    private fun populateExperimentFields(
        configName: String,
        configSpec: APIConfig,
        evalResult: ConfigEvaluation,
        result: ClientConfig,
    ) {
        result.isUserInExperiment = evalResult.isExperimentGroup
        result.isExperimentActive = configSpec.isActive

        if (configSpec.hasSharedParams != true) {
            return
        }

        result.isInLayer = true
        result.explicitParameters = configSpec.explicitParameters ?: emptyArray()
        result.secondaryExposures = evalResult.secondaryExposures

        val layerName = specStore.getLayerNameForExperiment(configName) ?: return
        val layer = specStore.getLayerConfig(layerName) ?: return

        // TODO: verify this is safe
        val layerValue = layer.defaultValue as Map<String, Any>
        val currentValue = result.value as Map<String, Any>
        result.value = layerValue + currentValue
    }

    private fun populateLayerFields(configSpec: APIConfig, evalResult: ConfigEvaluation, result: ClientConfig) {
        val delegate = evalResult.configDelegate
        result.explicitParameters = configSpec.explicitParameters ?: emptyArray()

        if (delegate != null && delegate != "") {
            val delegateSpec = specStore.getConfig(delegate)
            var delegateResult = ConfigEvaluation()
            evalFun(user, delegateSpec, delegateResult)
            if (delegateSpec != null) {
                result.allocatedExperimentName = hashName(delegate)
                result.isUserInExperiment = delegateResult.isExperimentGroup
                result.isExperimentActive = delegateSpec.isActive
                result.explicitParameters = delegateSpec.explicitParameters ?: emptyArray()
                result.secondaryExposures = delegateResult.secondaryExposures
            }
        }

        result.undelegatedSecondaryExposures = evalResult.undelegatedSecondaryExposures
    }

    private fun configToResponse(configName: String, configSpec: APIConfig): ClientConfig? {
        if (configSpec.entity == "segment" || configSpec.entity == "holdout") {
            return null
        }

        if (!configSpecIsForThisTargetApp(configSpec)) {
            return null
        }

        val evalResult = ConfigEvaluation()
        evalFun(user, configSpec, evalResult)
        val hashedName = hashName(configName)

        val result = ClientConfig(
            hashedName,
            "value" to false,
            evalResult.ruleID,
            evalResult.secondaryExposures,
        )
        val category = configSpec.type
        val entityType = configSpec.entity
        if (category == "feature_gate") {
            result.value = evalResult.booleanValue
            return result
        } else if (category == "dynamic_config") {
            result.value = evalResult.jsonValue ?: emptyMap<Any, Any>()
            result.group = evalResult.ruleID
            result.isDeviceBased = configSpec.idType.lowercase() == "stableid"

            if (entityType == "experiment") {
                populateExperimentFields(
                    configName,
                    configSpec,
                    evalResult,
                    result,
                )
            } else if (entityType == "layer") {
                populateLayerFields(configSpec, evalResult, result)
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
