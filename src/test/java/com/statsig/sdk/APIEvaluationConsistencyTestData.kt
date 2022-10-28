package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class APIEvaluationConsistencyTestData(
    @SerializedName("data") val data: Array<APITestDataSet>
)

internal data class APITestDataSet(
    @SerializedName("user") val user: StatsigUser,
    @SerializedName("feature_gates_v2") val gates: Map<String, APIFeatureGate>,
    @SerializedName("dynamic_configs") val configs: Map<String, APIDynamicConfig>,
    @SerializedName("layer_configs") val layers: Map<String, APIDynamicConfig>,
)