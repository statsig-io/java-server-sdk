package com.statsig.sdk

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CompletableFuture

sealed class StatsigServer {
    internal abstract val errorBoundary: ErrorBoundary

    @JvmSynthetic abstract suspend fun initialize()

    @JvmSynthetic abstract suspend fun checkGate(user: StatsigUser, gateName: String): Boolean

    @JvmSynthetic
    abstract suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun getExperimentWithExposureLoggingDisabled(
        user: StatsigUser,
        experimentName: String
    ): DynamicConfig

    @JvmSynthetic
    abstract suspend fun getExperimentInLayerForUser(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean = false
    ): DynamicConfig

    @JvmSynthetic abstract suspend fun getLayer(user: StatsigUser, layerName: String): Layer

    @JvmSynthetic abstract suspend fun getLayerWithCustomExposureLogging(user: StatsigUser, layerName: String, onExposure: OnLayerExposure): Layer

    @JvmSynthetic abstract suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer

    @JvmSynthetic abstract suspend fun shutdownSuspend()

    @JvmSynthetic abstract fun overrideGate(gateName: String, gateValue: Boolean)

    @JvmSynthetic abstract fun overrideConfig(configName: String, configValue: Map<String, Any>)

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

    abstract fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: Double,
        metadata: Map<String, String>? = null
    )

    abstract fun initializeAsync(): CompletableFuture<Unit>

    abstract fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean>

    abstract fun getConfigAsync(
        user: StatsigUser,
        dynamicConfigName: String
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentAsync(
        user: StatsigUser,
        experimentName: String
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        experimentName: String
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentInLayerForUserAsync(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean = false
    ): CompletableFuture<DynamicConfig>

    abstract fun getLayerAsync(
        user: StatsigUser,
        layerName: String
    ): CompletableFuture<Layer>

    abstract fun getLayerWithCustomExposureLoggingAsync(
        user: StatsigUser,
        layerName: String,
        onExposure: OnLayerExposure
    ): CompletableFuture<Layer>

    abstract fun getLayerWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer>

    /**
     * @deprecated
     * - we make no promises of support for this API
     */
    abstract fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>>

    abstract fun shutdown()

    internal abstract suspend fun flush()

    companion object {

        @JvmStatic
        @JvmOverloads
        fun create(
            serverSecret: String,
            options: StatsigOptions = StatsigOptions()
        ): StatsigServer = StatsigServerImpl(serverSecret, options)
    }
}

private class StatsigServerImpl(serverSecret: String, private val options: StatsigOptions) :
    StatsigServer() {

    init {
        if (serverSecret.isEmpty() || !serverSecret.startsWith("secret-")) {
            throw StatsigUninitializedException(
                "Statsig Server SDKs must be initialized with a secret key"
            )
        }
    }

    override val errorBoundary = ErrorBoundary(serverSecret, options)
    private val coroutineExceptionHandler =
        CoroutineExceptionHandler { _, ex ->
            // no-op - supervisor job should not throw when a child fails
            errorBoundary.logException(ex)
        }
    private val statsigJob = SupervisorJob()
    private val statsigScope = CoroutineScope(statsigJob + coroutineExceptionHandler)
    private val mutex = Mutex()
    private val statsigMetadata = StatsigMetadata.asMap()
    private val network = StatsigNetwork(serverSecret, options, statsigMetadata)
    private var configEvaluator = Evaluator()
    private var logger: StatsigLogger = StatsigLogger(statsigScope, network, statsigMetadata)
    private val pollingJob =
        statsigScope.launch(start = CoroutineStart.LAZY) {
            network.pollForChanges().collect {
                if (it == null || !it.hasUpdates) {
                    return@collect
                }
                configEvaluator.setDownloadedConfigs(it)
                fireRulesUpdatedCallback(it)
            }
        }
    private val idListPollingJob =
        statsigScope.launch(start = CoroutineStart.LAZY) {
            network.syncIDLists(configEvaluator)
        }

    private fun fireRulesUpdatedCallback(configSpecs: APIDownloadedConfigs) {
        if (options.rulesUpdatedCallback == null) {
            return
        }
        try {
            val configString = configSpecs.toString()
            options.rulesUpdatedCallback?.invoke(configString)
        } catch (e: Exception) {}
    }

    override suspend fun initialize() {
        errorBoundary.swallow {
            mutex.withLock { // Prevent multiple coroutines from calling this at once.
                if (pollingJob.isActive) {
                    return@swallow // Just return. Initialize was already called.
                }
                if (pollingJob.isCancelled || pollingJob.isCompleted) {
                    throw StatsigIllegalStateException(
                        "Cannot re-initialize server that has shutdown. Please recreate the server connection."
                    )
                }
                val downloadedConfigs = if (options.bootstrapValues != null) {
                    network.parseConfigSpecs(options.bootstrapValues)
                } else {
                    network.downloadConfigSpecs()
                }
                if (downloadedConfigs != null) {
                    configEvaluator.setDownloadedConfigs(downloadedConfigs)
                    if (options.bootstrapValues == null) {
                        // only fire the callback if this wasnt the result of a bootstrap
                        fireRulesUpdatedCallback(downloadedConfigs)
                    }
                }
                network.getAllIDLists(configEvaluator)
                pollingJob.start()
                idListPollingJob.start()
            }
        }
    }

    override suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        if (!isSDKInitialized()) {
            return false
        }

        return errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            var result: ConfigEvaluation = configEvaluator.checkGate(normalizedUser, gateName)
            if (result.fetchFromServer) {
                result = network.checkGate(normalizedUser, gateName)
            } else {
                logger.logGateExposure(
                    normalizedUser,
                    gateName,
                    result.booleanValue,
                    result.ruleID,
                    result.secondaryExposures,
                )
            }
            return@capture result.booleanValue
        }, { return@capture false })
    }

    override suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(dynamicConfigName)
        }
        return this.errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            return@capture getConfigHelper(normalizedUser, dynamicConfigName, false)
        }, {
            return@capture DynamicConfig.empty(dynamicConfigName)
        })
    }

    override suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(experimentName)
        }
        return this.errorBoundary.capture({
            return@capture getConfig(user, experimentName)
        }, {
            return@capture DynamicConfig.empty(experimentName)
        })
    }

    override suspend fun getExperimentWithExposureLoggingDisabled(
        user: StatsigUser,
        experimentName: String
    ): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(experimentName)
        }
        return this.errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            return@capture getConfigHelper(normalizedUser, experimentName, true)
        }, {
            return@capture DynamicConfig.empty(experimentName)
        })
    }

    override suspend fun getExperimentInLayerForUser(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean
    ): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty()
        }
        return this.errorBoundary.capture({
            val normalizedUser = normalizeUser(user)
            val experiments =
                configEvaluator.layers[layerName] ?: return@capture DynamicConfig.empty()
            for (expName in experiments) {
                if (configEvaluator.isUserOverriddenToExperiment(user, expName)) {
                    return@capture getConfigHelper(normalizedUser, expName, disableExposure)
                }
            }
            for (expName in experiments) {
                if (configEvaluator.isUserAllocatedToExperiment(user, expName)) {
                    return@capture getConfigHelper(normalizedUser, expName, disableExposure)
                }
            }
            // User is not allocated to any experiment at this point
            return@capture DynamicConfig.empty()
        }, {
            return@capture DynamicConfig.empty()
        })
    }

    override suspend fun getLayer(user: StatsigUser, layerName: String): Layer {
        return this.errorBoundary.capture({
            return@capture getLayerImpl(user, layerName, false)
        }, {
            return@capture Layer.empty(layerName)
        })
    }

    override suspend fun getLayerWithCustomExposureLogging(user: StatsigUser, layerName: String, onExposure: OnLayerExposure): Layer {
        return this.errorBoundary.capture({
            return@capture getLayerImpl(user, layerName, false, onExposure)
        }, {
            return@capture Layer.empty(layerName)
        })
    }

    override suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer {
        return this.errorBoundary.capture({
            return@capture getLayerImpl(user, layerName, true)
        }, {
            return@capture Layer.empty(layerName)
        })
    }

    override fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: String?,
        metadata: Map<String, String>?
    ) {
        this.logEventImpl(user, eventName, null, value, metadata)
    }

    override fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: Double,
        metadata: Map<String, String>?
    ) {
        this.logEventImpl(user, eventName, value, null, metadata)
    }

    private fun logEventImpl(
        user: StatsigUser?,
        eventName: String,
        doubleValue: Double?,
        stringValue: String?,
        metadata: Map<String, String>?
    ) {
        if (!isSDKInitialized()) {
            return
        }
        errorBoundary.swallowSync {
            val normalizedUser = normalizeUser(user)
            val event =
                StatsigEvent(
                    eventName = eventName,
                    eventValue = doubleValue ?: stringValue,
                    eventMetadata = metadata,
                    user = normalizedUser,
                )

            logger.log(event)
        }
    }

    override suspend fun shutdownSuspend() {
        if (!isSDKInitialized()) {
            return
        }
        errorBoundary.swallow {
            // CAUTION: Order matters here! Need to clean up jobs and post logs before
            // shutting down the network and supervisor scope
            pollingJob.cancelAndJoin()
            logger.shutdown()
            network.shutdown()
            statsigJob.cancelAndJoin()
            statsigScope.cancel()
        }
    }

    override fun overrideGate(gateName: String, gateValue: Boolean) {
        if (!isSDKInitialized()) {
            return
        }
        configEvaluator.overrideGate(gateName, gateValue)
    }

    override fun overrideConfig(configName: String, configValue: Map<String, Any>) {
        if (!isSDKInitialized()) {
            return
        }
        configEvaluator.overrideConfig(configName, configValue)
    }

    override fun initializeAsync(): CompletableFuture<Unit> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(Unit)
        }
        return statsigScope.future { initialize() }
    }

    override fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        return statsigScope.future {
            return@future checkGate(user, gateName)
        }
    }

    override fun getConfigAsync(
        user: StatsigUser,
        dynamicConfigName: String
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getConfig(user, dynamicConfigName)
        }
    }

    override fun getExperimentAsync(
        user: StatsigUser,
        experimentName: String
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperiment(user, experimentName)
        }
    }

    override fun getExperimentWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        experimentName: String
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperimentWithExposureLoggingDisabled(user, experimentName)
        }
    }

    override fun getExperimentInLayerForUserAsync(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperimentInLayerForUser(user, layerName, disableExposure)
        }
    }

    override fun getLayerAsync(
        user: StatsigUser,
        layerName: String
    ): CompletableFuture<Layer> {
        return statsigScope.future {
            return@future getLayer(user, layerName)
        }
    }

    override fun getLayerWithCustomExposureLoggingAsync(
        user: StatsigUser,
        layerName: String,
        onExposure: OnLayerExposure
    ): CompletableFuture<Layer> {
        return statsigScope.future {
            return@future getLayerWithCustomExposureLogging(user, layerName, onExposure)
        }
    }

    override fun getLayerWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer> {
        return statsigScope.future {
            return@future getLayerWithExposureLoggingDisabled(user, layerName)
        }
    }

    /**
     * @deprecated
     * - we make no promises of support for this API
     */
    override fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>> {
        return configEvaluator.getVariants(experimentName)
    }

    override fun shutdown() {
        runBlocking { shutdownSuspend() }
    }

    override suspend fun flush() {
        if (!isSDKInitialized()) {
            return
        }
        logger.flush()
    }

    private suspend fun getLayerImpl(user: StatsigUser, layerName: String, disableExposure: Boolean, onExposure: OnLayerExposure? = null): Layer {
        return this.errorBoundary.capture({
            isSDKInitialized()
            val normalizedUser = normalizeUser(user)

            var result: ConfigEvaluation = configEvaluator.getLayer(normalizedUser, layerName)
            if (result.fetchFromServer) {
                result = network.getConfig(user, layerName)
            }

            val value = (result.jsonValue as? Map<*, *>) ?: mapOf<String, Any>()

            return@capture Layer(
                layerName,
                result.ruleID,
                result.groupName,
                value as Map<String, Any>,
                result.secondaryExposures,
                result.configDelegate ?: "",
            ) exposureFun@{ layer, paramName ->
                val metadata = createLayerExposureMetadata(layer, paramName, result)
                if (disableExposure) {
                    return@exposureFun
                }
                if (onExposure != null) {
                    onExposure(
                        LayerExposureEventData(
                            normalizedUser,
                            layer,
                            paramName,
                            Gson().toJson(metadata)
                        )
                    )
                } else {
                    logger.logLayerExposure(
                        user,
                        metadata
                    )
                }
            }
        }, {
            return@capture Layer.empty(layerName)
        })
    }

    private fun normalizeUser(user: StatsigUser?): StatsigUser {
        val normalizedUser = user ?: StatsigUser("")
        if (options.getEnvironment() != null && user?.statsigEnvironment == null) {
            normalizedUser.statsigEnvironment = options.getEnvironment()
        }
        return normalizedUser
    }

    private fun isSDKInitialized(): Boolean {
        if (statsigJob.isCancelled || statsigJob.isCompleted) {
            println("StatsigServer was shutdown")
            return false
        }
        if (!pollingJob.isActive) { // If the server was never initialized
            println("Must initialize a server before calling other APIs")
            return false
        }
        return true
    }

    private suspend fun getConfigHelper(
        user: StatsigUser,
        configName: String,
        disableExposure: Boolean = false
    ): DynamicConfig {
        val result: ConfigEvaluation = configEvaluator.getConfig(user, configName)
        return this.getDynamicConfigFromEvalResult(result, user, configName, disableExposure)
    }

    private suspend fun getDynamicConfigFromEvalResult(
        result: ConfigEvaluation,
        user: StatsigUser,
        configName: String,
        disableExposure: Boolean = false
    ): DynamicConfig {
        var finalResult = result
        if (finalResult.fetchFromServer) {
            finalResult = network.getConfig(user, configName)
        } else if (!disableExposure) {
            logger.logConfigExposure(user, configName, finalResult.ruleID, finalResult.secondaryExposures)
        }
        return DynamicConfig(
            configName,
            finalResult.jsonValue as Map<String, Any>,
            finalResult.ruleID,
            finalResult.groupName,
            finalResult.secondaryExposures,
        )
    }
}
