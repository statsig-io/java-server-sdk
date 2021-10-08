package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class APIDownloadedConfigs(
    @SerializedName("dynamic_configs") val dynamicConfigs: Array<APIConfig>,
    @SerializedName("feature_gates") val featureGates: Array<APIConfig>,
    @SerializedName("time") val time: Long,
    @SerializedName("has_updates") val hasUpdates: Boolean,
)

internal data class APIConfig(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("salt") val salt: String,
    @SerializedName("defaultValue") val defaultValue: Any,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rules") val rules: Array<APIRule>,
)

internal data class APIRule(
    @SerializedName("name") val name: String,
    @SerializedName("passPercentage") val passPercentage: Int,
    @SerializedName("returnValue") val returnValue: Any,
    @SerializedName("id") val id: String,
    @SerializedName("salt") val salt: String?,
    @SerializedName("conditions") val conditions: Array<APICondition>,
)

internal data class APICondition(
    @SerializedName("type") val type: String,
    @SerializedName("targetValue") val targetValue: Any,
    @SerializedName("operator") val operator: String,
    @SerializedName("field") val field: String,
    @SerializedName("additionalValues") val additionalValues: Map<String, Any>
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
