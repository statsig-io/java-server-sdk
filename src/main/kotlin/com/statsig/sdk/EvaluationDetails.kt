package com.statsig.sdk

class EvaluationDetails(
    var configSyncTime: Long,
    var initTime: Long,
    var reason: EvaluationReason,
) {
    var serverTime: Long = Utils.getTimeInMillis()

    fun toMap(): Map<String, String> {
        return mapOf(
            "reason" to this.reason.toString(),
            "configSyncTime" to this.configSyncTime.toString(),
            "initTime" to this.initTime.toString(),
            "serverTime" to this.serverTime.toString(),
        )
    }
}

enum class EvaluationReason(val reason: String) {
    NETWORK("Network"),
    LOCAL_OVERRIDE("LocalOverride"),
    UNRECOGNIZED("Unrecognized"),
    UNINITIALIZED("Uninitialized"),
    BOOTSTRAP("Bootstrap"),
    DATA_ADAPTER("DataAdapter"),
    UNSUPPORTED("Unsupported"),
    DEFAULT("Default"),
    PERSISTED("Persisted"),
}
