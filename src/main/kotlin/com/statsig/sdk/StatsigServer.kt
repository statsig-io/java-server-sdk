package com.statsig.sdk

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections.emptyMap
import java.util.concurrent.CompletableFuture

sealed class StatsigServer {
    internal abstract var errorBoundary: ErrorBoundary
    abstract var initialized: Boolean

    @JvmSynthetic abstract fun setup(
        serverSecret: String,
        options: StatsigOptions,
    )

    @JvmSynthetic abstract suspend fun initialize(
        serverSecret: String,
        options: StatsigOptions,
    )

    @JvmSynthetic abstract suspend fun checkGate(user: StatsigUser, gateName: String): Boolean

    @JvmSynthetic abstract suspend fun checkGateWithExposureLoggingDisabled(user: StatsigUser, gateName: String): Boolean

    @JvmSynthetic
    abstract suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun getConfigWithExposureLoggingDisabled(user: StatsigUser, dynamicConfigName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun manuallyLogConfigExposure(user: StatsigUser, configName: String)

    @JvmSynthetic
    abstract suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig

    @JvmSynthetic
    abstract suspend fun manuallyLogLayerParameterExposure(user: StatsigUser, layerName: String, paramName: String)

    @JvmSynthetic
    abstract suspend fun manuallyLogGateExposure(user: StatsigUser, gateName: String)

    @JvmSynthetic
    abstract suspend fun getExperimentWithExposureLoggingDisabled(
        user: StatsigUser,
        experimentName: String,
    ): DynamicConfig

    @JvmSynthetic
    abstract suspend fun getExperimentInLayerForUser(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean = false,
    ): DynamicConfig

    @JvmSynthetic abstract suspend fun getLayer(user: StatsigUser, layerName: String): Layer

    @JvmSynthetic abstract suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer

    @JvmSynthetic abstract fun overrideLayer(layerName: String, value: Map<String, Any>)

    @JvmSynthetic abstract fun removeLayerOverride(layerName: String)

    @JvmSynthetic abstract suspend fun shutdownSuspend()

    @JvmSynthetic abstract fun overrideGate(gateName: String, gateValue: Boolean)

    @JvmSynthetic abstract fun removeGateOverride(gateName: String)

    @JvmSynthetic abstract fun overrideConfig(configName: String, configValue: Map<String, Any>)

    @JvmSynthetic abstract fun removeConfigOverride(configName: String)

    abstract fun getClientInitializeResponse(
        user: StatsigUser,
        hash: HashAlgo = HashAlgo.SHA256,
        clientSDKKey: String? = null,
    ): Map<String, Any>

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
        metadata: Map<String, String>? = null,
    )

    fun logEvent(user: StatsigUser?, eventName: String, value: Double) {
        logEvent(user, eventName, value, null)
    }

    abstract fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: Double,
        metadata: Map<String, String>? = null,
    )

    abstract fun initializeAsync(serverSecret: String, options: StatsigOptions): CompletableFuture<Void?>

    abstract fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean>
    abstract fun checkGateWithExposureLoggingDisabledAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean>

    abstract fun getConfigAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getConfigWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentInLayerForUserAsync(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean = false,
    ): CompletableFuture<DynamicConfig>

    abstract fun getLayerAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer>

    abstract fun getLayerWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer>

    abstract fun overrideLayerAsync(layerName: String, value: Map<String, Any>): CompletableFuture<Unit>
    abstract fun removeLayerOverrideAsync(layerName: String): CompletableFuture<Unit>
    abstract fun removeConfigOverrideAsync(configName: String): CompletableFuture<Unit>
    abstract fun removeGateOverrideAsync(gateName: String): CompletableFuture<Unit>

    abstract fun manuallyLogLayerParameterExposureAsync(user: StatsigUser, layerName: String, paramName: String): CompletableFuture<Void>
    abstract fun manuallyLogGateExposureAsync(user: StatsigUser, gateName: String): CompletableFuture<Void>
    abstract fun manuallyLogConfigExposureAsync(user: StatsigUser, configName: String): CompletableFuture<Void>

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
        fun create(): StatsigServer = StatsigServerImpl()
    }
}

private class StatsigServerImpl() :
    StatsigServer() {

    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

    override lateinit var errorBoundary: ErrorBoundary
    private lateinit var coroutineExceptionHandler: CoroutineExceptionHandler
    private lateinit var statsigJob: CompletableJob
    private lateinit var statsigScope: CoroutineScope
    private lateinit var network: StatsigNetwork
    private lateinit var logger: StatsigLogger
    private lateinit var configEvaluator: Evaluator
    private lateinit var diagnostics: Diagnostics
    private var options: StatsigOptions = StatsigOptions()
    private val mutex = Mutex()
    private val statsigMetadata = StatsigMetadata()
    override var initialized = false

    override fun setup(serverSecret: String, options: StatsigOptions) {
        Thread.setDefaultUncaughtExceptionHandler(MainThreadExceptionHandler(this, Thread.currentThread()))
        errorBoundary = ErrorBoundary(serverSecret, options, statsigMetadata)
        coroutineExceptionHandler = CoroutineExceptionHandler { _, ex ->
            // no-op - supervisor job should not throw when a child fails
            errorBoundary.logException("coroutineExceptionHandler", ex)
        }
        statsigJob = SupervisorJob()
        statsigScope = CoroutineScope(statsigJob + coroutineExceptionHandler)
        network = StatsigNetwork(serverSecret, options, statsigMetadata, errorBoundary)
        logger = StatsigLogger(statsigScope, network, statsigMetadata, options)
        this.options = options
    }

    override suspend fun initialize(serverSecret: String, options: StatsigOptions) {
        setup(serverSecret, options)
        initializeImpl(serverSecret, options)
    }

    private suspend fun initializeImpl(serverSecret: String, options: StatsigOptions) {
        errorBoundary.capture(
            "initialize",
            {
                mutex.withLock { // Prevent multiple coroutines from calling this at once.
                    if (this::configEvaluator.isInitialized && configEvaluator.isInitialized) {
                        throw StatsigIllegalStateException(
                            "Cannot re-initialize server that has shutdown. Please recreate the server connection.",
                        )
                    }
                    setupAndStartDiagnostics()
                    configEvaluator =
                        Evaluator(network, options, statsigScope, errorBoundary, diagnostics, statsigMetadata, serverSecret)
                    configEvaluator.initialize()
                    initialized = true
                    endInitDiagnostics(isSDKInitialized())
                }
            },
            {
                endInitDiagnostics(false)
            },
        )
    }

    override suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        return errorBoundary.capture("checkGate", {
            var result = checkGateImpl(user, gateName)
            if (!result.fetchFromServer) { // If we fetched from server, we've already logged
                logGateExposureImpl(user, gateName, result)
            }
            return@capture result.booleanValue
        }, { return@capture false }, configName = gateName)
    }

    override suspend fun checkGateWithExposureLoggingDisabled(user: StatsigUser, gateName: String): Boolean {
        return errorBoundary.capture("checkGateWithExposureLoggingDisabled", {
            val result = checkGateImpl(user, gateName)
            return@capture result.booleanValue
        }, { return@capture false }, configName = gateName)
    }

    private suspend fun checkGateImpl(user: StatsigUser, gateName: String): ConfigEvaluation {
        if (!isSDKInitialized()) {
            return ConfigEvaluation(false, false)
        }
        val normalizedUser = normalizeUser(user)

        var result: ConfigEvaluation = configEvaluator.checkGate(normalizedUser, gateName)

        if (result.fetchFromServer) {
            result = network.checkGate(normalizedUser, gateName, true)
        }
        return result
    }

    override suspend fun manuallyLogGateExposure(user: StatsigUser, gateName: String) {
        errorBoundary.swallow("manuallyLogGateExposure") {
            val result = checkGateImpl(user, gateName)
            logGateExposureImpl(user, gateName, result, true)
        }
    }

    private fun logGateExposureImpl(user: StatsigUser, gateName: String, evaluation: ConfigEvaluation, isManualExposure: Boolean = false) {
        logger.logGateExposure(
            normalizeUser(user),
            gateName,
            evaluation.booleanValue,
            evaluation.ruleID,
            evaluation.secondaryExposures,
            isManualExposure,
            evaluation.evaluationDetails,
        )
    }

    override suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(dynamicConfigName)
        }
        return this.errorBoundary.capture("getConfig", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(user, dynamicConfigName)
            logConfigImpl(normalizedUser, dynamicConfigName, result)
            return@capture getDynamicConfigFromEvalResult(result, user, dynamicConfigName)
        }, {
            return@capture DynamicConfig.empty(dynamicConfigName)
        }, configName = dynamicConfigName)
    }

    override suspend fun getConfigWithExposureLoggingDisabled(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(dynamicConfigName)
        }
        return this.errorBoundary.capture("getConfigWithExposureLoggingDisabled", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(normalizedUser, dynamicConfigName)
            return@capture getDynamicConfigFromEvalResult(result, normalizedUser, dynamicConfigName)
        }, {
            return@capture DynamicConfig.empty(dynamicConfigName)
        }, configName = dynamicConfigName)
    }

    override suspend fun manuallyLogConfigExposure(user: StatsigUser, configName: String) {
        errorBoundary.swallow("manuallyLogConfigExposure") {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(normalizedUser, configName)
            logConfigImpl(normalizedUser, configName, result, isManualExposure = true)
        }
    }

    override suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(experimentName)
        }
        return this.errorBoundary.capture("getExperiment", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(user, experimentName)
            logConfigImpl(normalizedUser, experimentName, result)
            return@capture getDynamicConfigFromEvalResult(result, user, experimentName)
        }, {
            return@capture DynamicConfig.empty(experimentName)
        }, configName = experimentName)
    }

    override suspend fun getExperimentWithExposureLoggingDisabled(
        user: StatsigUser,
        experimentName: String,
    ): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(experimentName)
        }
        return this.errorBoundary.capture("getExperimentWithExposureLoggingDisabled", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(normalizedUser, experimentName)
            return@capture getDynamicConfigFromEvalResult(result, user, experimentName)
        }, {
            return@capture DynamicConfig.empty(experimentName)
        }, configName = experimentName)
    }

    override suspend fun getExperimentInLayerForUser(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean,
    ): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty()
        }
        return this.errorBoundary.capture("getExperimentInLayerForUser", {
            val normalizedUser = normalizeUser(user)
            val experiments =
                configEvaluator.getExperimentsInLayer(layerName)
            for (expName in experiments) {
                if (configEvaluator.isUserOverriddenToExperiment(user, expName)) {
                    val result = getConfigImpl(normalizedUser, expName)
                    if (!disableExposure) {
                        logConfigImpl(normalizedUser, expName, result)
                    }
                    return@capture getDynamicConfigFromEvalResult(result, user, expName)
                }
            }
            for (expName in experiments) {
                if (configEvaluator.isUserAllocatedToExperiment(user, expName)) {
                    val result = getConfigImpl(normalizedUser, expName)
                    if (!disableExposure) {
                        logConfigImpl(normalizedUser, expName, result)
                    }
                    return@capture getDynamicConfigFromEvalResult(result, user, expName)
                }
            }
            // User is not allocated to any experiment at this point
            return@capture DynamicConfig.empty()
        }, {
            return@capture DynamicConfig.empty()
        }, configName = layerName)
    }

    override suspend fun getLayer(user: StatsigUser, layerName: String): Layer {
        return this.errorBoundary.capture("getLayer", {
            return@capture getLayerImpl(user, layerName, false)
        }, {
            return@capture Layer.empty(layerName)
        }, configName = layerName)
    }

    override suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer {
        return this.errorBoundary.capture("getLayerWithExposureLoggingDisabled", {
            return@capture getLayerImpl(user, layerName, true)
        }, {
            return@capture Layer.empty(layerName)
        }, configName = layerName)
    }

    override fun getClientInitializeResponse(
        user: StatsigUser,
        hash: HashAlgo,
        clientSDKKey: String?,
    ): Map<String, Any> {
        return this.errorBoundary.captureSync("getClientInitializeResponse", {
            val normalizedUser = normalizeUser(user)
            return@captureSync configEvaluator.getClientInitializeResponse(normalizedUser, hash, clientSDKKey)
        }, { return@captureSync emptyMap() })
    }

    override fun overrideLayer(layerName: String, value: Map<String, Any>) {
        this.errorBoundary.captureSync("overrideLayer", {
            isSDKInitialized()
            configEvaluator.overrideLayer(layerName, value)
        }, { return@captureSync })
    }
    override fun removeLayerOverride(layerName: String) {
        this.errorBoundary.captureSync("removeLayerOverride", {
            isSDKInitialized()
            configEvaluator.removeLayerOverride(layerName)
        }, { return@captureSync })
    }

    override fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: String?,
        metadata: Map<String, String>?,
    ) {
        this.errorBoundary.captureSync("logEvent:string", {
            this.logEventImpl(user, eventName, null, value, metadata)
        }, { return@captureSync })
    }

    override fun logEvent(
        user: StatsigUser?,
        eventName: String,
        value: Double,
        metadata: Map<String, String>?,
    ) {
        this.errorBoundary.captureSync("logEvent:double", {
            this.logEventImpl(user, eventName, value, null, metadata)
        }, { return@captureSync })
    }

    private fun logEventImpl(
        user: StatsigUser?,
        eventName: String,
        doubleValue: Double?,
        stringValue: String?,
        metadata: Map<String, String>?,
    ) {
        if (!isSDKInitialized()) {
            return
        }
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

    override suspend fun shutdownSuspend() {
        if (!isSDKInitialized()) {
            return
        }
        errorBoundary.swallow("shutdownSuspend") {
            // CAUTION: Order matters here! Need to clean up jobs and post logs before
            // shutting down the network and supervisor scope
            logger.shutdown()
            network.shutdown()
            configEvaluator.shutdown()
            statsigJob.cancelAndJoin()
            statsigScope.cancel()
            initialized = false
        }
    }

    override fun overrideGate(gateName: String, gateValue: Boolean) {
        errorBoundary.captureSync("overrideGate", {
            isSDKInitialized()
            configEvaluator.overrideGate(gateName, gateValue)
        }, { return@captureSync })
    }

    override fun removeGateOverride(gateName: String) {
        errorBoundary.captureSync("removeGateOverride", {
            isSDKInitialized()
            configEvaluator.removeGateOverride(gateName)
        }, { return@captureSync })
    }

    override fun overrideConfig(configName: String, configValue: Map<String, Any>) {
        errorBoundary.captureSync("overrideConfig", {
            isSDKInitialized()
            configEvaluator.overrideConfig(configName, configValue)
        }, { return@captureSync })
    }

    override fun removeConfigOverride(configName: String) {
        errorBoundary.captureSync("removeConfigOverride", {
            isSDKInitialized()
            configEvaluator.removeConfigOverride(configName)
        }, { return@captureSync })
    }

    override fun initializeAsync(serverSecret: String, options: StatsigOptions): CompletableFuture<Void?> {
        setup(serverSecret, options)
        return statsigScope.future {
            initializeImpl(serverSecret, options)
            null
        }
    }

    override fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        return statsigScope.future {
            return@future checkGate(user, gateName)
        }
    }

    override fun checkGateWithExposureLoggingDisabledAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        return statsigScope.future {
            return@future checkGateWithExposureLoggingDisabled(user, gateName)
        }
    }

    override fun getConfigAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getConfig(user, dynamicConfigName)
        }
    }

    override fun getConfigWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getConfigWithExposureLoggingDisabled(user, dynamicConfigName)
        }
    }

    override fun getExperimentAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperiment(user, experimentName)
        }
    }

    override fun getExperimentWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperimentWithExposureLoggingDisabled(user, experimentName)
        }
    }

    override fun getExperimentInLayerForUserAsync(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean,
    ): CompletableFuture<DynamicConfig> {
        return statsigScope.future {
            return@future getExperimentInLayerForUser(user, layerName, disableExposure)
        }
    }

    override fun getLayerAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer> {
        return statsigScope.future {
            return@future getLayer(user, layerName)
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

    override fun overrideLayerAsync(layerName: String, value: Map<String, Any>): CompletableFuture<Unit> {
        return statsigScope.future {
            return@future overrideLayer(layerName, value)
        }
    }

    override fun removeLayerOverrideAsync(layerName: String): CompletableFuture<Unit> {
        return statsigScope.future {
            return@future removeLayerOverride(layerName)
        }
    }
    override fun removeConfigOverrideAsync(configName: String): CompletableFuture<Unit> {
        return statsigScope.future {
            return@future removeConfigOverride(configName)
        }
    }
    override fun removeGateOverrideAsync(gateName: String): CompletableFuture<Unit> {
        return statsigScope.future {
            return@future removeGateOverride(gateName)
        }
    }

    override fun manuallyLogLayerParameterExposureAsync(user: StatsigUser, layerName: String, paramName: String): CompletableFuture<Void> {
        return statsigScope.future {
            manuallyLogLayerParameterExposure(user, layerName, paramName)
        }.thenApply { return@thenApply null }
    }

    override fun manuallyLogGateExposureAsync(user: StatsigUser, gateName: String): CompletableFuture<Void> {
        return statsigScope.future {
            manuallyLogGateExposure(user, gateName)
        }.thenApply { return@thenApply null }
    }

    override fun manuallyLogConfigExposureAsync(user: StatsigUser, configName: String): CompletableFuture<Void> {
        return statsigScope.future {
            manuallyLogConfigExposure(user, configName)
        }.thenApply { return@thenApply null }
    }

    /**
     *
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
        if (!isSDKInitialized()) {
            return Layer.empty(layerName)
        }
        return this.errorBoundary.capture("getLayerImpl", {
            val normalizedUser = normalizeUser(user)

            var result: ConfigEvaluation = configEvaluator.getLayer(normalizedUser, layerName)
            if (result.fetchFromServer) {
                result = network.getConfig(user, layerName, disableExposure)
            }

            val value = (result.jsonValue as? Map<*, *>) ?: mapOf<String, Any>()

            return@capture Layer(
                layerName,
                result.ruleID,
                result.groupName,
                value as Map<String, Any>,
                result.secondaryExposures,
                result.configDelegate,
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
                            gson.toJson(metadata),
                        ),
                    )
                } else {
                    logLayerExposureImpl(user, metadata)
                }
            }
        }, {
            return@capture Layer.empty(layerName)
        })
    }

    override suspend fun manuallyLogLayerParameterExposure(
        user: StatsigUser,
        layerName: String,
        paramName: String,
    ) {
        val normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.getLayer(normalizedUser, layerName)
        if (result.fetchFromServer) {
            result = network.getConfig(user, layerName, disableExposureLogging = true)
        }
        val value = (result.jsonValue as? Map<*, *>) ?: mapOf<String, Any>()

        val layer = Layer(
            layerName,
            result.ruleID,
            result.groupName,
            value as Map<String, Any>,
            result.secondaryExposures,
            result.configDelegate,
        )
        var metadata = createLayerExposureMetadata(layer, paramName, result)
        metadata.isManualExposure = "true"

        logLayerExposureImpl(user, metadata)
    }

    private fun logLayerExposureImpl(user: StatsigUser, metadata: LayerExposureMetadata) {
        logger.logLayerExposure(
            user,
            metadata,
        )
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
        if (!this::configEvaluator.isInitialized || !configEvaluator.isInitialized) { // If the server was never initialized
            println("Must initialize a server before calling other APIs")
            return false
        }
        return true
    }

    private fun getConfigImpl(
        user: StatsigUser,
        configName: String,
    ): ConfigEvaluation {
        return configEvaluator.getConfig(user, configName)
    }
    private fun logConfigImpl(user: StatsigUser, configName: String, result: ConfigEvaluation, isManualExposure: Boolean = false) {
        logger.logConfigExposure(user, configName, result.ruleID, result.secondaryExposures, isManualExposure, result.evaluationDetails)
    }
    private suspend fun getDynamicConfigFromEvalResult(
        result: ConfigEvaluation,
        user: StatsigUser,
        configName: String,
    ): DynamicConfig {
        var finalResult = result
        if (finalResult.fetchFromServer) {
            finalResult = network.getConfig(user, configName, disableExposureLogging = true)
        }
        return DynamicConfig(
            configName,
            (finalResult.jsonValue ?: emptyMap<String, Any>()) as Map<String, Any>,
            finalResult.ruleID,
            finalResult.groupName,
            finalResult.secondaryExposures,
        )
    }

    private fun setupAndStartDiagnostics() {
        diagnostics = Diagnostics(options.disableDiagnostics, logger)
        errorBoundary.diagnostics = diagnostics
        logger.diagnostics = diagnostics
        diagnostics.markStart(KeyType.OVERALL)
    }

    private fun endInitDiagnostics(success: Boolean) {
        diagnostics?.markEnd(KeyType.OVERALL, success)
        diagnostics?.logDiagnostics(ContextType.INITIALIZE)
        diagnostics.diagnosticsContext = ContextType.CONFIG_SYNC
    }

    class MainThreadExceptionHandler(val server: StatsigServer, val currentThread: Thread) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            if (!t.name.equals(currentThread.name)) {
                throw e
            }
            println("[Statsig]: Shutting down Statsig because of unhandled exception from your server")
            server.shutdown()
            throw e
        }
    }
}
