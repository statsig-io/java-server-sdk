package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

internal data class ClientInitializeResponse(
    @SerializedName("feature_gates") var feature_gates: Map<String, ClientConfig>,
    @SerializedName("dynamic_configs") var dynamic_configs: Map<String, ClientConfig>,
    @SerializedName("layer_configs") var layer_configs: Map<String, ClientConfig>,
    @SerializedName("sdkParams") var sdkParams: Map<String, Any>,
    @SerializedName("has_updates") var has_updates: Boolean,
    @SerializedName("time") var time: Long,
    @SerializedName("generator") var generator: String,
    @SerializedName("evaluated_keys") var evaluated_keys: Map<String, Any>,
    @SerializedName("hash_used") var hash_used: String,
) {
    fun toMap(): Map<String, Any> {
        val gson = Gson()
        val json = gson.toJson(this)
        return gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
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
)

internal class ClientInitializeFormatter(
    private val specStore: SpecStore,
    private val evalFun: (user: StatsigUser, config: APIConfig?) -> ConfigEvaluation,
    private val user: StatsigUser,
    private val hash: HashAlgo = HashAlgo.SHA256,
    private val clientSDKKey: String? = null,
) {

    fun getFormattedResponse(): Map<String, Any> {
        val evaluatedKeys = mutableMapOf<String, Any>()
        if (user.userID != null) {
            evaluatedKeys["userID"] = user.userID!!
        }
        if (user.customIDs != null && user.customIDs!!.keys.isNotEmpty()) {
            evaluatedKeys["customIDs"] = user.customIDs!!
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

        return ClientInitializeResponse(
            mapFn(specStore.getAllGates()),
            mapFn(specStore.getAllConfigs()),
            mapFn(specStore.getAllLayerConfigs()),
            emptyMap(),
            true,
            0, // set the time to 0 so this doesn't interfere with polling,
            "statsig-java-sdk",
            evaluatedKeys,
            this.hash.toString().lowercase(),
        ).toMap()
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
        result.secondaryExposures = cleanExposures(evalResult.secondaryExposures)

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
            val delegateResult = evalFun(user, delegateSpec)
            if (delegateSpec != null) {
                result.allocatedExperimentName = hashName(delegate)
                result.isUserInExperiment = delegateResult.isExperimentGroup
                result.isExperimentActive = delegateSpec.isActive
                result.explicitParameters = delegateSpec.explicitParameters ?: emptyArray()
                result.secondaryExposures = cleanExposures(delegateResult.secondaryExposures)
            }
        }

        result.undelegatedSecondaryExposures = cleanExposures(evalResult.undelegatedSecondaryExposures)
    }

    private fun configToResponse(configName: String, configSpec: APIConfig): ClientConfig? {
        if (configSpec.entity == "segment" || configSpec.entity == "holdout") {
            return null
        }

        if (!configSpecIsForThisTargetApp(configSpec)) {
            return null
        }

        val evalResult = evalFun(user, configSpec)
        val hashedName = hashName(configName)
        val result = ClientConfig(
            hashedName,
            "value" to false,
            evalResult.ruleID,
            cleanExposures(evalResult.secondaryExposures),
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

    private fun cleanExposures(exposures: ArrayList<Map<String, String>>): ArrayList<Map<String, String>> {
        val res: ArrayList<Map<String, String>> = ArrayList()
        var seen = emptySet<String>()
        exposures.forEach {
            val key = "${it["gate"]}|${it["gateValue"]}|${it["ruleID"]}"
            if (seen.contains(key)) return@forEach
            seen = seen.plus(key)
            res.add(it)
        }
        return res
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
