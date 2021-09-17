package com.statsig.sdk

import com.google.gson.annotations.SerializedName

data class APIEvaluationConsistencyTestData(
    @SerializedName("data") val data: Array<APITestDataSet>
)

data class APITestDataSet(
    @SerializedName("user") val user: StatsigUser,
    @SerializedName("feature_gates") val gates: Map<String, Boolean>,
    @SerializedName("dynamic_configs") val configs: Map<String, APIConfigData>,
)

data class APIConfigData(
    @SerializedName("rule_id") val ruleID: String,
    @SerializedName("name") val name : String,
    @SerializedName("group") val group : String? = null,
    @SerializedName("value") val value: Map<String, Any>,
)
