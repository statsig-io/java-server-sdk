package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import com.statsig.sdk.datastore.IDataStore
import com.statsig.sdk.network.STATSIG_API_URL_BASE

private const val TIER_KEY: String = "tier"
private const val DEFAULT_INIT_TIME_OUT_MS: Long = 3000L
private const val CONFIG_SYNC_INTERVAL_MS: Long = 10 * 1000
private const val ID_LISTS_SYNC_INTERVAL_MS: Long = 60 * 1000
private val defaultLogger = object : LoggerInterface {

    override fun warning(message: String) {
        println(message)
    }

    override fun info(message: String) {
        println(message)
    }
}

/**
 * A SAM for Java compatibility
 */
@FunctionalInterface
fun interface RulesUpdatedCallback {
    fun accept(rules: String)
}

interface LoggerInterface {
    fun warning(message: String)
    fun info(message: String)
}

/**
 * An object of properties for initializing the sdk with advanced options
 * @property api the api endpoint to use for initialization and logging
 * @property initTimeoutMs the amount of time to wait for an initialize() response from the server
 * @property apiForDownloadConfigSpecs the api endpoint to use for initialization and logging
 * @property api the api endpoint to use for initialization and logging
 * @property proxyConfig the proxy config details for creating proxy agent
 * NOTE: gates/configs will still be fetched in the background if this time is exceeded, but the
 * callback to initialize will fire after, at most, the time specified
 */
class StatsigOptions(
    var api: String? = null,
    var initTimeoutMs: Long = DEFAULT_INIT_TIME_OUT_MS,
    var apiForDownloadConfigSpecs: String? = null,
    var apiForGetIdlists: String? = null,
    var bootstrapValues: String? = null,
    var rulesUpdatedCallback: RulesUpdatedCallback? = null,
    var localMode: Boolean = false,
    var disableDiagnostics: Boolean = false,
    var rulesetsSyncIntervalMs: Long = CONFIG_SYNC_INTERVAL_MS,
    var idListsSyncIntervalMs: Long = ID_LISTS_SYNC_INTERVAL_MS,
    var dataStore: IDataStore? = null,
    var customLogger: LoggerInterface = defaultLogger,
    var disableAllLogging: Boolean = false,
    var proxyConfig: ProxyConfig? = null,
    var endpointProxyConfigs: Map<NetworkEndpoint, ForwardProxyConfig> = mapOf(),
    var fallbackToStatsigAPI: Boolean = false,
    var disableIPResolution: Boolean = false,
) {
    constructor(api: String) : this(api, DEFAULT_INIT_TIME_OUT_MS)
    constructor(initTimeoutMs: Long) : this(STATSIG_API_URL_BASE, initTimeoutMs)

    private var environment: MutableMap<String, String>? = null

    fun setTier(tier: String) {
        setEnvironmentParameter(TIER_KEY, tier.lowercase())
    }

    fun setEnvironmentParameter(key: String, value: String) {
        val currentEnvironment = environment
        if (currentEnvironment == null) {
            environment = mutableMapOf(key to value)
        } else {
            currentEnvironment[key] = value
        }
    }

    fun getEnvironment(): MutableMap<String, String>? {
        return environment
    }

    fun getLoggingCopy(): Map<String, Any> {
        return mapOf(
            "api" to (api ?: "DEFAULT"),
            "initTimeoutMs" to initTimeoutMs,
            "localMode" to localMode,
            "disableDiagnostics" to disableDiagnostics,
            "rulesetsSyncIntervalMs" to rulesetsSyncIntervalMs,
            "idListsSyncIntervalMs" to idListsSyncIntervalMs,
            "setDataStore" to (dataStore != null),
            "setBootstrapValues" to (bootstrapValues != null),
            "disableAllLogging" to disableAllLogging,
        )
    }
}

enum class NetworkEndpoint {
    @SerializedName("download_config_specs")
    DOWNLOAD_CONFIG_SPECS,

    @SerializedName("get_id_lists")
    GET_ID_LISTS,

    @SerializedName("log_event")
    LOG_EVENT,

    @SerializedName("all")
    ALL_ENDPOINTS, // Default value for all endpoints
}

enum class NetworkProtocol {
    @SerializedName("http")
    HTTP,

    @SerializedName("grpc")
    GRPC,

    @SerializedName("grpc_websocket")
    GRPC_WEBSOCKET,
}

data class ForwardProxyConfig(
    @SerializedName("proxyAddress") var proxyAddress: String,
    @SerializedName("protocol") val proxyProtocol: NetworkProtocol,
)

data class ProxyConfig @JvmOverloads constructor(
    var proxyHost: String,
    var proxyPort: Int,
    var proxyAuth: String? = null, // Optional: pass in Credentials.basic("username", "password")
    val proxySchema: String = "https", // default to https
)

data class GetFeatureGateOptions(var disableExposureLogging: Boolean = false) {
    constructor() : this(false)
}

data class CheckGateOptions(var disableExposureLogging: Boolean = false) {
    constructor() : this(false)
}

data class GetConfigOptions(var disableExposureLogging: Boolean = false) {
    constructor() : this(false)
}

data class GetLayerOptions(var disableExposureLogging: Boolean = false) {
    constructor() : this(false)
}

data class GetExperimentOptions(var disableExposureLogging: Boolean = false) {
    constructor() : this(false)
}
