package com.statsig.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Properties
import java.util.concurrent.CompletableFuture

sealed class StatsigServer {

    @JvmSynthetic
    abstract suspend fun initialize()

    @JvmSynthetic
    abstract suspend fun checkGate(user: StatsigUser, gateName: String): Boolean

    @JvmSynthetic
    abstract suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun shutdownSuspend()

    fun logEvent(user: StatsigUser?, eventName: String) {
        logEvent(user, eventName, null)
    }

    fun logEvent(user: StatsigUser?, eventName: String, value: String? = null) {
        logEvent(user, eventName, value, null)
    }

    abstract fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: String? = null,
        metadata: Map<String, String>? = null
    )

    fun logEvent(user: StatsigUser?, eventName: String, value: Double) {
        logEvent(user, eventName, value, null)
    }

    abstract fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>? = null)

    abstract fun initializeAsync(): CompletableFuture<Unit>

    abstract fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean>

    abstract fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig>

    abstract fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig>

    abstract fun shutdown()

    internal abstract suspend fun flush()

    companion object {

        @JvmStatic
        @JvmOverloads
        fun create(serverSecret: String, options: StatsigOptions = StatsigOptions()): StatsigServer =
            StatsigServerImpl(serverSecret, options)
    }
}

private const val VERSION = "0.8.0"

private class StatsigServerImpl(
    serverSecret: String,
    private val options: StatsigOptions
) : StatsigServer() {

    init {
        if (serverSecret.isEmpty() || !serverSecret.startsWith("secret-")) {
            throw IllegalArgumentException(
                "Statsig Server SDKs must be initialized with a secret key"
            )
        }
    }

    private val version = try {
        val properties = Properties()
        properties.load(StatsigServerImpl::class.java.getResourceAsStream("/statsigsdk.properties"))
        properties.getProperty("version")
    } catch (e: Exception) {
        VERSION
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
        // no-op - supervisor job should not throw when a child fails
    }
    private val statsigJob = SupervisorJob()
    private val statsigScope = CoroutineScope(statsigJob + coroutineExceptionHandler)
    private val mutex = Mutex()
    private val statsigMetadata = mapOf("sdkType" to "java-server", "sdkVersion" to version)
    private val network = StatsigNetwork(serverSecret, options, statsigMetadata)
    private var configEvaluator = Evaluator()
    private var logger: StatsigLogger = StatsigLogger(statsigScope, network, statsigMetadata)
    private val pollingJob = statsigScope.launch(start = CoroutineStart.LAZY) {
        network.pollForChanges().collect {
            if (it == null || !it.hasUpdates) {
                return@collect
            }
            configEvaluator.setDownloadedConfigs(it)
        }
    }

    override suspend fun initialize() {
        mutex.withLock { // Prevent multiple coroutines from calling this at once.
            if (pollingJob.isActive) {
                return // Just return. Initialize was already called.
            }
            if (pollingJob.isCancelled || pollingJob.isCompleted) {
                throw IllegalStateException("Cannot re-initialize server that has shutdown. Please recreate the server connection.")
            }
            val downloadedConfigs = network.downloadConfigSpecs()
            if (downloadedConfigs != null) {
                configEvaluator.setDownloadedConfigs(downloadedConfigs)
            }
            pollingJob.start()
        }
    }

    override suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        enforceActive()
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

    override suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        enforceActive()
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

    override suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        enforceActive()
        return getConfig(user, experimentName)
    }

    override fun logEvent(user: StatsigUser?, eventName: String, value: String?, metadata: Map<String, String>?) {
        enforceActive()
        statsigScope.launch {
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
    }

    override fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>?) {
        enforceActive()
        statsigScope.launch {
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
    }

    override suspend fun shutdownSuspend() {
        enforceActive()
        // CAUTION: Order matters here! Need to clean up jobs and post logs before
        // shutting down the network and supervisor scope
        pollingJob.cancelAndJoin()
        logger.shutdown()
        network.shutdown()
        statsigJob.cancelAndJoin()
        statsigScope.cancel()
    }

    override fun initializeAsync(): CompletableFuture<Unit> {
        return statsigScope.future {
            initialize()
        }
    }

    override fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        return statsigScope.future {
            return@future checkGate(user, gateName)
        }
    }

    override fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getConfig(user, dynamicConfigName)
        }
    }

    override fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperiment(user, experimentName)
        }
    }

    override fun shutdown() {
        runBlocking {
            shutdownSuspend()
        }
    }

    override suspend fun flush() {
        logger.flush()
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        val normalizedUser = user ?: StatsigUser("")
        if (options.getEnvironment() != null && user?.statsigEnvironment == null) {
            normalizedUser.statsigEnvironment = options.getEnvironment()
        }
        return normalizedUser
    }

    private fun enforceActive() {
        if (statsigJob.isCancelled || statsigJob.isCompleted) {
            throw IllegalStateException("StatsigServer was shutdown")
        }
        if (!pollingJob.isActive) { // If the server was never initialized
            throw IllegalStateException("Must initialize a server before calling other APIs")
        }
    }
}
