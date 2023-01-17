package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

internal data class APIDownloadedConfigs(
    @SerializedName("dynamic_configs") val dynamicConfigs: Array<APIConfig>,
    @SerializedName("feature_gates") val featureGates: Array<APIConfig>,
    @SerializedName("layer_configs") val layerConfigs: Array<APIConfig>,
    @SerializedName("id_lists") val idLists: Map<String, Boolean>?,
    @SerializedName("layers") val layers: Map<String, Array<String>>?,
    @SerializedName("time") val time: Long = 0,
    @SerializedName("has_updates") val hasUpdates: Boolean,
)

internal data class APIConfig(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("salt") val salt: String,
    @SerializedName("defaultValue") val defaultValue: Any,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rules") val rules: Array<APIRule>,
    @SerializedName("idType") val idType: String,
    @SerializedName("entity") val entity: String,
    @SerializedName("explicitParameters") val explicitParameters: Array<String>?,
)

internal data class APIRule(
    @SerializedName("name") val name: String,
    @SerializedName("passPercentage") val passPercentage: Double,
    @SerializedName("returnValue") val returnValue: Any,
    @SerializedName("id") val id: String,
    @SerializedName("salt") val salt: String?,
    @SerializedName("conditions") val conditions: Array<APICondition>,
    @SerializedName("idType") val idType: String,
    @SerializedName("groupName") val groupName: String,
    @SerializedName("configDelegate") val configDelegate: String?,
)

internal data class APICondition(
    @SerializedName("type") val type: String,
    @SerializedName("targetValue") val targetValue: Any,
    @SerializedName("operator") val operator: String,
    @SerializedName("field") val field: String,
    @SerializedName("additionalValues") val additionalValues: Map<String, Any>,
    @SerializedName("idType") val idType: String,
)

internal data class APIFeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean,
    @SerializedName("rule_id") val ruleID: String?,
    @SerializedName("secondary_exposures")
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf()
)

internal data class APIDynamicConfig(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("rule_id") val ruleID: String? = "",
    @SerializedName("secondary_exposures")
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("undelegated_secondary_exposures")
    val undelegatedSecondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
)

internal data class IDList(
    @SerializedName("name") val name: String,
    @SerializedName("size") var size: Long = 0,
    @SerializedName("creationTime") var creationTime: Long = 0,
    @SerializedName("url") var url: String? = null,
    @SerializedName("fileID") var fileID: String? = null,
) {
    internal val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun contains(id: String): Boolean {
        return ids.contains(id)
    }

    fun add(id: String) {
        ids.add(id)
    }

    fun remove(id: String) {
        ids.remove(id)
    }
}

internal data class LayerExposureMetadata(
    @SerializedName("config") val config: String,
    @SerializedName("ruleID") val ruleID: String,
    @SerializedName("allocatedExperiment") val allocatedExperiment: String,
    @SerializedName("parameterName") val parameterName: String,
    @SerializedName("isExplicitParameter") val isExplicitParameter: String,
    @SerializedName("secondaryExposures") val secondaryExposures: ArrayList<Map<String, String>>,
    @SerializedName("isManualExposure") var isManualExposure: String = "false",
    @SerializedName("evaluationDetails") val evaluationDetails: EvaluationDetails?,
) {
    fun toStatsigEventMetadataMap(): Map<String, String> {
        return mapOf(
            "config" to config,
            "ruleID" to ruleID,
            "allocatedExperiment" to allocatedExperiment,
            "parameterName" to parameterName,
            "isExplicitParameter" to isExplicitParameter,
            "isManualExposure" to isManualExposure,
            // secondaryExposures excluded -- StatsigEvent adds secondaryExposures explicitly as a top level key
        )
    }
}
