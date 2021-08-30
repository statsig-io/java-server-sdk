package com.statsig.sdk

import com.google.gson.annotations.SerializedName

data class StatsigEvent(
    @SerializedName("eventName") private val eventName: String,
    @SerializedName("value") val eventValue: Any? = null,
    @SerializedName("metadata") val eventMetadata: Map<String, String>? = null,
    @SerializedName("user") var user: StatsigUser? = null,
    @SerializedName("statsigMetadata") val statsigMetadata: Map<String, String>? = null,
) {
    init {
        user = user?.copy()
        // We need to remove private attributes when logging events
        user?.privateAttributes = null
    }
}
