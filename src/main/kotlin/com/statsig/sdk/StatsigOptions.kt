package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import com.statsig.sdk.datastore.IDataStore
import com.statsig.sdk.network.STATSIG_API_URL_BASE
import com.statsig.sdk.persistent_storage.IUserPersistentStorage
import com.statsig.sdk.persistent_storage.UserPersistedValues
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private const val TIER_KEY: String = "tier"
private const val DEFAULT_INIT_TIME_OUT_MS: Long = 3000L
private const val CONFIG_SYNC_INTERVAL_MS: Long = 10 * 1000
private const val ID_LISTS_SYNC_INTERVAL_MS: Long = 60 * 1000

enum class LogLevel(val value: Int) {
    NONE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    ;

    fun getLevelString(): String {
        return when (this) {
            ERROR -> "ERROR"
            WARN -> "WARN"
            INFO -> "INFO"
            DEBUG -> "DEBUG"
            NONE -> ""
        }
    }
}

object OutputLogger {
    var logLevel: LogLevel = LogLevel.WARN

    fun error(message: String) {
        logMessage(LogLevel.ERROR, message)
    }

    fun warn(message: String) {
        logMessage(LogLevel.WARN, message)
    }

    fun info(message: String) {
        logMessage(LogLevel.INFO, message)
    }

    fun debug(message: String) {
        logMessage(LogLevel.DEBUG, message)
    }

    private fun logMessage(level: LogLevel, message: String) {
        if (level.value < logLevel.value) return

        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.MILLIS))
        println("$timestamp ${level.getLevelString()} [Statsig] $message")
    }
}

// Example Logger Interface you might use
interface LoggerInterface {
    fun error(message: String)
    fun warn(message: String)
    fun info(message: String)
    fun debug(message: String)
    fun setLogLevel(level: LogLevel)
}

// Example implementation of LoggerInterface
private val defaultLogger = object : LoggerInterface {
    override fun error(message: String) {
        OutputLogger.error(message)
    }

    override fun warn(message: String) {
        OutputLogger.warn(message)
    }

    override fun info(message: String) {
        OutputLogger.info(message)
    }

    override fun debug(message: String) {
        OutputLogger.debug(message)
    }

    override fun setLogLevel(level: LogLevel) {
        OutputLogger.logLevel = LogLevel.WARN
    }
}

/**
 * A SAM for Java compatibility
 */
@FunctionalInterface
fun interface RulesUpdatedCallback {
    fun accept(rules: String)
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
    var initializeSources: List<DataSource>? = null,
    var configSyncSources: List<DataSource>? = null,
    var fallbackToStatsigAPI: Boolean = false,
    var disableIPResolution: Boolean = false,
    var userPersistentStorage: IUserPersistentStorage? = null,
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

enum class DataSource {
    @SerializedName("network")
    NETWORK,

    @SerializedName("statsig_network")
    STATSIG_NETWORK,

    @SerializedName("datastore")
    DATA_STORE,

    @SerializedName("bootstrap")
    BOOTSTRAP,
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

enum class AuthenticationMode {
    @SerializedName("disabled")
    DISABLED,

    @SerializedName("tls")
    TLS,

    @SerializedName("mTls")
    MTLS,
}

data class ForwardProxyConfig(
    @SerializedName("proxyAddress") var proxyAddress: String,
    @SerializedName("protocol") val proxyProtocol: NetworkProtocol,
    // Websocket streaming failover spec
    @SerializedName("max_retry_attempt") val maxRetryAttempt: Int? = null,
    @SerializedName("retry_backoff_multiplier") val retryBackoffMultiplier: Int? = null,
    @SerializedName("retry_backoff_base_ms") val retryBackoffBaseMs: Long? = null,
    @SerializedName("push_worker_failover_threshold") val pushWorkerFailoverThreshold: Int? = null,

    // TLS Certification
    @SerializedName("authentication_mode") var authenticationMode: AuthenticationMode = AuthenticationMode.DISABLED,
    @SerializedName("tls_cert_chain") var tlsCertChain: InputStream? = null,
    @SerializedName("tls_private_key") var tlsPrivateKey: InputStream? = null,
    @SerializedName("tls_private_key_password") var tlsPrivateKeyPassword: InputStream? = null,
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

data class PersistentAssignmentOptions(
    var enforceTargeting: Boolean = false,
    var enforceOverrides: Boolean = false,
)

data class GetLayerOptions(
    var disableExposureLogging: Boolean = false,
    var userPersistedValues: UserPersistedValues? = null,
    var persistentAssignmentOptions: PersistentAssignmentOptions? = null,
) {
    constructor() : this(false)
}

data class GetExperimentOptions(
    var disableExposureLogging: Boolean = false,
    var userPersistedValues: UserPersistedValues? = null,
    var persistentAssignmentOptions: PersistentAssignmentOptions? = null,
) {
    constructor() : this(false)
}
