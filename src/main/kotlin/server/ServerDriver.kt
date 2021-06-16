package server

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

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

    suspend fun getExperiment(user: StatsigUser?, experimentName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling getExperiment")
        }
        return getConfig(user, experimentName);
    }

    suspend fun checkGate(user: StatsigUser?, gateName: String): Boolean {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling checkGate")
        }
        var result: ConfigEvaluation = configEvaluator.checkGate(user, gateName)
        if (result.fetchFromServer) {
            val networkResult = network.checkGate(user, gateName)
            result = runBlocking {
                return@runBlocking networkResult
            }
        }
        logger.logGateExposure(user, gateName, result.booleanValue, result.ruleID ?: "")
        return result.booleanValue
    }

    suspend fun getConfig(user: StatsigUser?, dynamicConfigName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling getConfig")
        }
        var result: ConfigEvaluation = configEvaluator.getConfig(user, dynamicConfigName)
        if (result.fetchFromServer) {
            val networkResult = network.getConfig(user, dynamicConfigName)
            result = runBlocking {
                return@runBlocking networkResult
            }
        }
        logger.logConfigExposure(user, dynamicConfigName, result.ruleID ?: "")
        return DynamicConfig(Config(dynamicConfigName, result.jsonValue as Map<String, Any>, result.ruleID))
    }

    fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: Double,
        metadata: Map<String, String>? = null,
    ) {
        val event = StatsigEvent(
            eventName = eventName,
            eventValue = value,
            eventMetadata = metadata,
            user = user,
        )
        logger.log(event)
    }

    fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: String? = null,
        metadata: Map<String, String>? = null,
    ) {
        val event = StatsigEvent(
            eventName = eventName,
            eventValue = value,
            eventMetadata = metadata,
            user = user,
        )
        logger.log(event)
    }

    fun shutdown() {
        pollingJob?.cancel()
        logger.flush()
    }
}
