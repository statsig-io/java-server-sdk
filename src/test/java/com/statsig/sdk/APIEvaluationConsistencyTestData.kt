package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import com.statsig.sdk.APIDynamicConfig
import com.statsig.sdk.APIFeatureGate

data class APIEvaluationConsistencyTestData(
    @SerializedName("data") val data: Array<APITestDataSet>
)

data class APITestDataSet(
    @SerializedName("user") val user: StatsigUser,
    @SerializedName("feature_gates_v2") val gates: Map<String, APIFeatureGate>,
    @SerializedName("dynamic_configs") val configs: Map<String, APIDynamicConfig>,
)