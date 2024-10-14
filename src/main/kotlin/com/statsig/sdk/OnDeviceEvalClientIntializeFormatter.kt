package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class OnDeviceEvalClientIntializeResponse(
    @SerializedName("feature_gates") var feature_gates: Map<String, APIConfig>,
    @SerializedName("dynamic_configs") var dynamic_configs: Map<String, APIConfig>,
    @SerializedName("layer_configs") var layer_configs: Map<String, APIConfig>,
    @SerializedName("has_updates") var has_updates: Boolean,
    @SerializedName("time") var time: Long,
    @SerializedName("sdkInfo") var sdkInfo: Map<String, String>,
) {
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        map["feature_gates"] = feature_gates
        map["dynamic_configs"] = dynamic_configs
        map["layer_configs"] = layer_configs
        map["has_updates"] = has_updates
        map["time"] = time
        map["sdkInfo"] = sdkInfo
        return map
    }

    fun isEmpty(): Boolean {
        return feature_gates.isEmpty() && dynamic_configs.isEmpty() && layer_configs.isEmpty()
    }
}

internal class OnDeviceEvalClientInitializeFormatter(
    private val specStore: SpecStore,
    private val clientSDKKey: String?,
) {
    fun getFormattedResponse(): OnDeviceEvalClientIntializeResponse {
        fun filterTargetApp(configSpecs: Map<String, APIConfig>): Map<String, APIConfig> {
            return configSpecs.filter { (_, configSpec) ->
                InitializeFormatterUtils.configSpecIsForThisTargetApp(clientSDKKey, specStore, configSpec)
            }
        }

        var gates = specStore.getAllGates()
        var configs = specStore.getAllConfigs()
        val layerConfigs = specStore.getAllLayerConfigs()

        if (clientSDKKey != null) {
            val entities = specStore.getEntitiesFromKey(clientSDKKey)
            if (entities != null) {
                gates = gates.filter { entities.gates.contains(it.key) }
                configs = configs.filter { entities.configs.contains(it.key) }
            }
        }

        val metadata = StatsigMetadata()

        return OnDeviceEvalClientIntializeResponse(
            filterTargetApp(gates),
            filterTargetApp(configs),
            filterTargetApp(layerConfigs),
            true,
            specStore.getLastUpdateTime(),
            mutableMapOf<String, String>().apply {
                this["sdkType"] = metadata.sdkType
                this["sdkVersion"] = metadata.sdkVersion
            },
        )
    }
}
