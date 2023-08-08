package com.statsig.sdk

import com.google.gson.annotations.SerializedName

data class Marker(
    @SerializedName("key") val key: KeyType? = null,
    @SerializedName("action") val action: ActionType? = null,
    @SerializedName("timestamp") val timestamp: Double? = null,
    @SerializedName("step") var step: StepType? = null,
    @SerializedName("statusCode") var statusCode: Int? = null,
    @SerializedName("success") var success: Boolean? = null,
    @SerializedName("url") var url: String? = null,
    @SerializedName("idListCount") var idListCount: Int? = null,
    @SerializedName("reason") var reason: String? = null,
    @SerializedName("sdkRegion") var sdkRegion: String? = null,
)

enum class ContextType {
    @SerializedName("initialize")
    INITIALIZE,

    @SerializedName("config_sync")
    CONFIG_SYNC,
}

enum class KeyType {
    @SerializedName("download_config_specs")
    DOWNLOAD_CONFIG_SPECS,

    @SerializedName("bootstrap")
    BOOTSTRAP,

    @SerializedName("get_id_list")
    GET_ID_LIST,

    @SerializedName("get_id_list_sources")
    GET_ID_LIST_SOURCES,

    @SerializedName("overall")
    OVERALL,
}

enum class StepType {
    @SerializedName("process")
    PROCESS,

    @SerializedName("network_request")
    NETWORK_REQUEST,
}

enum class ActionType {
    @SerializedName("start")
    START,

    @SerializedName("end")
    END,
}

typealias DiagnosticsMarkers = MutableMap<ContextType, MutableList<Marker>>
