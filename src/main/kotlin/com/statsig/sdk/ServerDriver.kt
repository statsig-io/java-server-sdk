package com.statsig.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Properties

internal class ServerDriver(
    private val coroutineScope: CoroutineScope,
    serverSecret: String,
    private val options: StatsigOptions = StatsigOptions()
) {
    private val network: StatsigNetwork
    private var configEvaluator: Evaluator
    @Volatile
    private var initialized: Boolean = false
    private var logger: StatsigLogger
    private val statsigMetadata: Map<String, String>
    private var pollingJob: Job? = null

    init {
        if (serverSecret.isEmpty() || !serverSecret.startsWith("secret-")) {
            throw IllegalArgumentException(
                    "Statsig Server SDKs must be initialized with a secret key"
            )
        }
        var version = "0.7.1+"
        try {
            val properties = Properties()
            properties.load(ServerDriver::class.java.getResourceAsStream("/statsigsdk.properties"))
            version = properties.getProperty("version")
        } catch (e: Exception) {}
        statsigMetadata = mapOf("sdkType" to "java-server", "sdkVersion" to version)

        network = StatsigNetwork(serverSecret, options, statsigMetadata)
        configEvaluator = Evaluator()
        logger = StatsigLogger(coroutineScope, network, statsigMetadata)
    }

    suspend fun initialize() {
        initialized = true
        val downloadedConfigs = network.downloadConfigSpecs()
        if (downloadedConfigs != null) {
            configEvaluator.setDownloadedConfigs(downloadedConfigs)
        }

        pollingJob = network.pollForChanges().onEach {
            if (it == null || !it.hasUpdates) {
                return@onEach
            }
            configEvaluator.setDownloadedConfigs(it)
        }.launchIn(coroutineScope)
    }

    suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling checkGate")
        }
        val normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.checkGate(normalizedUser, gateName)
        if (result.fetchFromServer) {
            result = network.checkGate(normalizedUser, gateName)
        } else {
            logger.logGateExposure(
                    normalizedUser,
                    gateName,
                    result.booleanValue,
                    result.ruleID ?: ""
            )
        }
        return result.booleanValue
    }

    suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling getConfig")
        }
        val normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.getConfig(normalizedUser, dynamicConfigName)
        if (result.fetchFromServer) {
            result = network.getConfig(normalizedUser, dynamicConfigName)
        } else {
            logger.logConfigExposure(normalizedUser, dynamicConfigName, result.ruleID ?: "")
        }
        return DynamicConfig(
                Config(dynamicConfigName, result.jsonValue as Map<String, Any>, result.ruleID)
        )
    }

    suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        if (!initialized) {
            throw IllegalStateException("Must initialize before calling getExperiment")
        }
        return getConfig(user, experimentName)
    }

    suspend fun logEvent(
            user: StatsigUser?,
            eventName: String,
            value: Double,
            metadata: Map<String, String>? = null,
    ) {
        val normalizedUser = normalizeUser(user)
        val event =
                StatsigEvent(
                        eventName = eventName,
                        eventValue = value,
                        eventMetadata = metadata,
                        user = normalizedUser,
                )
        logger.log(event)
    }

    suspend fun logEvent(
            user: StatsigUser?,
            eventName: String,
            value: String? = null,
            metadata: Map<String, String>? = null,
    ) {
        val normalizedUser = normalizeUser(user)
        val event =
                StatsigEvent(
                        eventName = eventName,
                        eventValue = value,
                        eventMetadata = metadata,
                        user = normalizedUser,
                )
        logger.log(event)
    }

    suspend fun shutdown() {
        pollingJob?.cancel()
        logger.shutdown()
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        val normalizedUser = user ?: StatsigUser("")
        if (options.getEnvironment() != null && user?.statsigEnvironment == null) {
            normalizedUser.statsigEnvironment = options.getEnvironment()
        }
        return normalizedUser
    }
}
