package server

import com.google.gson.annotations.SerializedName

data class StatsigEvent(
    @SerializedName("eventName") private val eventName: String,
    @SerializedName("value") val eventValue: Any? = null,
    @SerializedName("metadata") val eventMetadata: Map<String, String>? = null,
    @SerializedName("user") val user: StatsigUser? = null,
    @SerializedName("statsigMetadata") val statsigMetadata: Map<String, String>? = null,
)
