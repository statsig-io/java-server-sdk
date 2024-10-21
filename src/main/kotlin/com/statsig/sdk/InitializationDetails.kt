package com.statsig.sdk

import com.google.gson.annotations.SerializedName

data class InitializationDetails(
    @SerializedName("duration")
    var duration: Long,
    @SerializedName("isSDKReady")
    var isSDKReady: Boolean,
    @SerializedName("configSpecReady")
    var configSpecReady: Boolean,
    @SerializedName("failureDetails")
    var failureDetails: FailureDetails? = null,
)

data class ConfigSyncDetails(
    @SerializedName("duration")
    var duration: Long,
    @SerializedName("configSpecReady")
    var configSpecReady: Boolean,
    @SerializedName("failureDetails")
    var failureDetails: FailureDetails? = null,
    @SerializedName("lcut")
    var lcut: Long? = null,
)

data class FailureDetails(
    @SerializedName("reason") var reason: FailureReason,
    @SerializedName("exception") var exception: Exception? = null,
    @SerializedName("statusCode") var statusCode: Int? = null,
)

enum class FailureReason {
    CONFIG_SPECS_NETWORK_ERROR,
    EMPTY_SPEC,
    INTERNAL_ERROR,
    PARSE_RESPONSE_ERROR,
}
