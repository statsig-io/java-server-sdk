package server

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture

class ServerDriver(private val serverSecret: String, private val options: StatsigOptions = StatsigOptions()) {
    private val network: StatsigNetwork
    private var configEvaluator: Evaluator
    private var initialized: Boolean = false
    private var logger: StatsigLogger
    private val statsigMetadata: Map<String, String> = mapOf("sdkType" to "java-server", "sdkVersion" to "1.0.0")
    private var pollingJob: Job? = null

    init {
        if (serverSecret.isEmpty() || !serverSecret.startsWith("secret-")) {
            throw IllegalArgumentException("Statsig Server SDKs must be initialized with a secret key")
        }
        network = StatsigNetwork(serverSecret, options, statsigMetadata)
        configEvaluator = Evaluator()
        logger = StatsigLogger(network, statsigMetadata)
    }

    suspend fun initialize() {
        initialized = true
        val downloadedConfigs = network.downloadConfigSpecs()
        runBlocking {
            configEvaluator.setDownloadedConfigs(downloadedConfigs)
        }

        pollingJob = network.pollForChanges {
            if (it == null || !it.hasUpdates) {
                return@pollForChanges
            }
            configEvaluator.setDownloadedConfigs(it)
        }
    }

    suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling checkGate")
        }
        var normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.checkGate(normalizedUser, gateName)
        if (result.fetchFromServer) {
            val networkResult = network.checkGate(normalizedUser, gateName)
            result = runBlocking {
                return@runBlocking networkResult
            }
        }
        logger.logGateExposure(normalizedUser, gateName, result.booleanValue, result.ruleID ?: "")
        return result.booleanValue
    }

    suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling getConfig")
        }
        var normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.getConfig(normalizedUser, dynamicConfigName)
        if (result.fetchFromServer) {
            val networkResult = network.getConfig(normalizedUser, dynamicConfigName)
            result = runBlocking {
                return@runBlocking networkResult
            }
        }
        logger.logConfigExposure(normalizedUser, dynamicConfigName, result.ruleID ?: "")
        return DynamicConfig(Config(dynamicConfigName, result.jsonValue as Map<String, Any>, result.ruleID))
    }

    suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling getExperiment")
        }
        return getConfig(user, experimentName);
    }

    fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: Double,
        metadata: Map<String, String>? = null,
    ) {
        var normalizedUser = normalizeUser(user)
        val event = StatsigEvent(
            eventName = eventName,
            eventValue = value,
            eventMetadata = metadata,
            user = normalizedUser,
        )
        logger.log(event)
    }

    fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: String? = null,
        metadata: Map<String, String>? = null,
    ) {
        var normalizedUser = normalizeUser(user)
        val event = StatsigEvent(
            eventName = eventName,
            eventValue = value,
            eventMetadata = metadata,
            user = normalizedUser,
        )
        logger.log(event)
    }

    fun shutdown() {
        pollingJob?.cancel()
        logger.flush()
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser? {
        var normalizedUser = user
        if (user == null && options.getEnvironment() != null) {
            normalizedUser = StatsigUser("")
        }
        normalizedUser?.statsigEnvironment = options.getEnvironment()
        return normalizedUser
    }

    /**
     * Async methods expose functionality in a friendly way to Java (via CompleteableFutures in Java 8)
     * Below is, essentially, the "Java" API, which calls into the kotlin implementation above
     * NOTE: Non async functions like logevent can be used by both without an additional function signature
     */

    fun initializeAsync(): CompletableFuture<Unit> = GlobalScope.future {
        return@future initialize()
    }

    fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> = GlobalScope.future {
        return@future checkGate(user, gateName)
    }

    fun getConfigAsync(user: StatsigUser, configName: String): CompletableFuture<DynamicConfig> = GlobalScope.future {
        return@future getConfig(user, configName)
    }

    fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> = GlobalScope.future {
        return@future getExperiment(user, experimentName)
    }
}
