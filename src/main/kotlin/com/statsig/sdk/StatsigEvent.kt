package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import kotlin.collections.ArrayList

internal data class StatsigEvent(
    @SerializedName("eventName") val eventName: String,
    @SerializedName("value") val eventValue: Any? = null,
    @SerializedName("metadata") var eventMetadata: Map<String, String>? = null,
    @SerializedName("user") var user: StatsigUser? = null,
    @SerializedName("statsigMetadata") val statsigMetadata: Map<String, String>? = null,
    @SerializedName("secondaryExposures") val secondaryExposures: ArrayList<Map<String, String>>? = arrayListOf(),
    @SerializedName("time") val time: Long? = Utils.getTimeInMillis(),
) {
    init {
        // We need to use a special copy of the user object that strips out private attributes for logging purposes
        user = user?.getCopyForLogging()
    }
}
