package com.statsig.sdk

import com.google.gson.annotations.SerializedName

internal data class StatsigEvent(
    @SerializedName("eventName") private val eventName: String,
    @SerializedName("value") val eventValue: Any? = null,
    @SerializedName("metadata") val eventMetadata: Map<String, String>? = null,
    @SerializedName("user") var user: StatsigUser? = null,
    @SerializedName("statsigMetadata") val statsigMetadata: Map<String, String>? = null,
) {
    init {
        // We need to use a special copy of the user object that strips out private attributes for logging purposes
        user = user?.getCopyForLogging()
    }
}
