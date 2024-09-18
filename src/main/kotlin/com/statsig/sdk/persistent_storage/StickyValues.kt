package com.statsig.sdk.persistent_storage

import com.google.gson.annotations.SerializedName

class StickyValues(
    @SerializedName("value") val value: Boolean = false,
    @SerializedName("json_value") val jsonValue: Any? = null,
    @SerializedName("rule_id") val ruleID: String = "",
    @SerializedName("group_name") val groupName: String? = null,
    @SerializedName("secondary_exposures") val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("explicit_parameters") val explicitParameters: Array<String>? = null,
    @SerializedName("config_delegate") val configDelegate: String? = null,
    @SerializedName("undelegated_secondary_exposures") val undelegatedSecondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("time") var time: Long,
)
