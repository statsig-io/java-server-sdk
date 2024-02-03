package com.statsig.sdk

import com.google.gson.annotations.SerializedName

data class Marker(
    @SerializedName("markerID") var markerID: String? = null,
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
    @SerializedName("configName") var configName: String? = null,
)

enum class ContextType {
    @SerializedName("initialize")
    INITIALIZE,

    @SerializedName("config_sync")
    CONFIG_SYNC,

    @SerializedName("api_call")
    API_CALL,

    @SerializedName("get_client_initialize_response")
    GET_CLIENT_INITIALIZE_RESPONSE,
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

    @SerializedName("check_gate")
    CHECK_GATE,

    @SerializedName("get_config")
    GET_CONFIG,

    @SerializedName("get_experiment")
    GET_EXPERIMENT,

    @SerializedName("get_layer")
    GET_LAYER,

    @SerializedName("get_client_initialize_response")
    GET_CLIENT_INITIALIZE_RESPONSE, ;

    companion object {
        fun convertFromString(value: String): KeyType? {
            return when (value) {
                in "checkGate", "checkGateWithExposureLoggingDisabled" ->
                    CHECK_GATE
                in "getExperiment", "getExperimentWithExposureLoggingDisabled" ->
                    GET_EXPERIMENT
                in "getConfig", "getConfigWithExposureLoggingDisabled" ->
                    GET_CONFIG
                in "getLayer", "getLayerWithExposureLoggingDisabled" ->
                    GET_LAYER
                else ->
                    null
            }
        }
    }
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
