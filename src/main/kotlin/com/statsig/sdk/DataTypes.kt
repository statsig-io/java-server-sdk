package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

internal data class APIDownloadedConfigs(
    @SerializedName("dynamic_configs") val dynamicConfigs: Array<APIConfig>,
    @SerializedName("feature_gates") val featureGates: Array<APIConfig>,
    @SerializedName("id_lists") val idLists: Map<String, Boolean>?,
    @SerializedName("layers") val layers: Map<String, Array<String>>?,
    @SerializedName("layer_configs") val layerConfigs: Map<String, APILayerConfig>,
    @SerializedName("time") val time: Long = 0,
    @SerializedName("has_updates") val hasUpdates: Boolean,
)

internal data class APILayerConfig(
    @SerializedName("allocation_rules") val allocationRules: Array<APIRule>,
    @SerializedName("default_values") val defaultValues: Map<String, Any>,
)

internal data class APIConfig(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("salt") val salt: String,
    @SerializedName("defaultValue") val defaultValue: Any,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rules") val rules: Array<APIRule>,
    @SerializedName("idType") val idType: String,
)

internal data class APIRule(
    @SerializedName("name") val name: String,
    @SerializedName("passPercentage") val passPercentage: Int,
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
)

internal data class IDList(
    @SerializedName("name") val name: String,
    @SerializedName("size") var size: Long = 0,
    @SerializedName("creationTime") var creationTime: Long = 0,
    @SerializedName("url") var url: String? = null,
    @SerializedName("fileID") var fileID: String? = null,
) {
    internal val ids: MutableSet<String> = ConcurrentHashMap.newKeySet();
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