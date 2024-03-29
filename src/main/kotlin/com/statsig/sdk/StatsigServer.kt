package com.statsig.sdk

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.statsig.sdk.datastore.LocalFileDataStore
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

    abstract fun isInitialized(): Boolean

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

    abstract fun overrideLayer(layerName: String, value: Map<String, Any>)

    abstract fun removeLayerOverride(layerName: String)

    @JvmSynthetic abstract suspend fun shutdownSuspend()

    abstract fun overrideGate(gateName: String, gateValue: Boolean)

    abstract fun removeGateOverride(gateName: String)

    abstract fun overrideConfig(configName: String, configValue: Map<String, Any>)

    abstract fun removeConfigOverride(configName: String)

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

    abstract fun checkGateSync(user: StatsigUser, gateName: String, option: CheckGateOptions? = null): Boolean

    abstract fun checkGateSync(user: StatsigUser, gateName: String): Boolean

    @JvmSynthetic
    abstract fun getFeatureGate(user: StatsigUser, gateName: String, option: GetFeatureGateOptions?): APIFeatureGate

    @JvmSynthetic
    abstract fun getFeatureGate(user: StatsigUser, gateName: String): APIFeatureGate

    abstract fun checkGateWithExposureLoggingDisabledAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean>

    abstract fun getConfigAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getConfigSync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): DynamicConfig

    abstract fun getConfigSync(
        user: StatsigUser,
        dynamicConfigName: String,
        option: GetConfigOptions? = null,
    ): DynamicConfig

    abstract fun getConfigWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig>

    abstract fun getExperimentSync(
        user: StatsigUser,
        experimentName: String,
        option: GetExperimentOptions? = null,
    ): DynamicConfig

    abstract fun getExperimentSync(
        user: StatsigUser,
        experimentName: String,
    ): DynamicConfig

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

    abstract fun getLayerSync(
        user: StatsigUser,
        layerName: String,
        option: GetLayerOptions? = null,
    ): Layer

    abstract fun getLayerSync(
        user: StatsigUser,
        layerName: String,
    ): Layer

    abstract fun getLayerWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer>

    @JvmSynthetic abstract fun overrideLayerAsync(layerName: String, value: Map<String, Any>): CompletableFuture<Unit>

    @JvmSynthetic abstract fun removeLayerOverrideAsync(layerName: String): CompletableFuture<Unit>

    @JvmSynthetic abstract fun removeConfigOverrideAsync(configName: String): CompletableFuture<Unit>

    @JvmSynthetic abstract fun removeGateOverrideAsync(gateName: String): CompletableFuture<Unit>

    abstract fun manuallyLogLayerParameterExposureAsync(user: StatsigUser, layerName: String, paramName: String): CompletableFuture<Void>
    abstract fun manuallyLogGateExposureAsync(user: StatsigUser, gateName: String): CompletableFuture<Void>
    abstract fun manuallyLogConfigExposureAsync(user: StatsigUser, configName: String): CompletableFuture<Void>

    abstract fun manuallyLogExperimentExposureAsync(user: StatsigUser, experimentName: String): CompletableFuture<Void>

    /**
     * @deprecated
     * - we make no promises of support for this API
     */
    abstract fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>>

    abstract fun shutdown()

    @JvmSynthetic
    internal abstract suspend fun flush()

    @JvmSynthetic internal abstract fun getCustomLogger(): LoggerInterface

    abstract fun localDataStoreSetUp(serverSecret: String)

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
                    localDataStoreSetUp(serverSecret)
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

    override fun localDataStoreSetUp(serverSecret: String) {
        if (options.dataStore == null || options.dataStore !is LocalFileDataStore) {
            return
        }

        val localFileDataStore = options.dataStore as LocalFileDataStore
        localFileDataStore.filePath = Hashing.djb2(serverSecret)
    }

    override fun isInitialized(): Boolean {
        return initialized
    }

    override suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        if (!isSDKInitialized()) {
            return false
        }
        return errorBoundary.capture("checkGate", {
            val result = checkGateImpl(user, gateName)
            logGateExposureImpl(user, gateName, result)
            return@capture result.booleanValue
        }, { return@capture false }, configName = gateName)
    }

    override fun checkGateSync(user: StatsigUser, gateName: String): Boolean {
        if (!isSDKInitialized()) {
            return false
        }
        return errorBoundary.captureSync("checkGateSync", {
            val result = checkGateImpl(user, gateName)
            logGateExposureImpl(user, gateName, result)
            return@captureSync result.booleanValue
        }, { return@captureSync false }, configName = gateName)
    }

    override fun checkGateSync(user: StatsigUser, gateName: String, option: CheckGateOptions?): Boolean {
        if (!isSDKInitialized()) {
            return false
        }
        return errorBoundary.captureSync("checkGateSync", {
            val result = checkGateImpl(user, gateName)
            if (option?.disableExposureLogging !== true) {
                logGateExposureImpl(user, gateName, result)
            }
            return@captureSync result.booleanValue
        }, { return@captureSync false }, configName = gateName)
    }

    override fun getFeatureGate(user: StatsigUser, gateName: String): APIFeatureGate {
        if (!isSDKInitialized()) {
            return APIFeatureGate(gateName, false, null, arrayListOf(), EvaluationReason.UNINITIALIZED)
        }
        return errorBoundary.captureSync("getFeatureGate", {
            val result = checkGateImpl(user, gateName)
            logGateExposureImpl(user, gateName, result)
            return@captureSync APIFeatureGate(gateName, result.booleanValue, result.ruleID, result.secondaryExposures, result.evaluationDetails?.reason)
        }, { return@captureSync APIFeatureGate(gateName, false, null, arrayListOf(), EvaluationReason.DEFAULT) }, configName = gateName)
    }

    override fun getFeatureGate(user: StatsigUser, gateName: String, option: GetFeatureGateOptions?): APIFeatureGate {
        if (!isSDKInitialized()) {
            return APIFeatureGate(gateName, false, null, arrayListOf(), EvaluationReason.UNINITIALIZED)
        }
        return errorBoundary.captureSync("getFeatureGate", {
            val result = checkGateImpl(user, gateName)
            if (option?.disableExposureLogging !== true) {
                logGateExposureImpl(user, gateName, result)
            }
            return@captureSync APIFeatureGate(gateName, result.booleanValue, result.ruleID, result.secondaryExposures, result.evaluationDetails?.reason)
        }, { return@captureSync APIFeatureGate(gateName, false, null, arrayListOf(), EvaluationReason.DEFAULT) }, configName = gateName)
    }

    override suspend fun checkGateWithExposureLoggingDisabled(user: StatsigUser, gateName: String): Boolean {
        if (!isSDKInitialized()) {
            return false
        }
        return errorBoundary.capture("checkGateWithExposureLoggingDisabled", {
            val result = checkGateImpl(user, gateName)
            return@capture result.booleanValue
        }, { return@capture false }, configName = gateName)
    }

    private fun checkGateImpl(user: StatsigUser, gateName: String): ConfigEvaluation {
        if (!isSDKInitialized()) {
            return ConfigEvaluation(false, false)
        }
        val normalizedUser = normalizeUser(user)

        return configEvaluator.checkGate(normalizedUser, gateName)
    }

    override suspend fun manuallyLogGateExposure(user: StatsigUser, gateName: String) {
        if (!isSDKInitialized()) {
            return
        }
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
            return@capture getDynamicConfigFromEvalResult(result, dynamicConfigName)
        }, {
            return@capture DynamicConfig.empty(dynamicConfigName)
        }, configName = dynamicConfigName)
    }

    override fun getConfigSync(user: StatsigUser, dynamicConfigName: String, option: GetConfigOptions?): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(dynamicConfigName)
        }
        return this.errorBoundary.captureSync("getConfigSync", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(user, dynamicConfigName)
            if (option?.disableExposureLogging !== true) {
                logConfigImpl(normalizedUser, dynamicConfigName, result)
            }
            return@captureSync getDynamicConfigFromEvalResult(result, dynamicConfigName)
        }, {
            return@captureSync DynamicConfig.empty(dynamicConfigName)
        }, configName = dynamicConfigName)
    }

    override fun getConfigSync(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(dynamicConfigName)
        }
        return this.errorBoundary.captureSync("getConfigSync", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(user, dynamicConfigName)
            logConfigImpl(normalizedUser, dynamicConfigName, result)
            return@captureSync getDynamicConfigFromEvalResult(result, dynamicConfigName)
        }, {
            return@captureSync DynamicConfig.empty(dynamicConfigName)
        }, configName = dynamicConfigName)
    }

    override suspend fun getConfigWithExposureLoggingDisabled(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(dynamicConfigName)
        }
        return this.errorBoundary.capture("getConfigWithExposureLoggingDisabled", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(normalizedUser, dynamicConfigName)
            return@capture getDynamicConfigFromEvalResult(result, dynamicConfigName)
        }, {
            return@capture DynamicConfig.empty(dynamicConfigName)
        }, configName = dynamicConfigName)
    }

    override suspend fun manuallyLogConfigExposure(user: StatsigUser, configName: String) {
        if (!isSDKInitialized()) {
            return
        }
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
            return@capture getDynamicConfigFromEvalResult(result, experimentName)
        }, {
            return@capture DynamicConfig.empty(experimentName)
        }, configName = experimentName)
    }

    override fun getExperimentSync(user: StatsigUser, experimentName: String, option: GetExperimentOptions?): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(experimentName)
        }
        return this.errorBoundary.captureSync("getExperimentSync", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(user, experimentName)
            if (option?.disableExposureLogging !== true) {
                logConfigImpl(normalizedUser, experimentName, result)
            }
            return@captureSync getDynamicConfigFromEvalResult(result, experimentName)
        }, {
            return@captureSync DynamicConfig.empty(experimentName)
        }, configName = experimentName)
    }

    override fun getExperimentSync(user: StatsigUser, experimentName: String): DynamicConfig {
        if (!isSDKInitialized()) {
            return DynamicConfig.empty(experimentName)
        }
        return this.errorBoundary.captureSync("getExperimentSync", {
            val normalizedUser = normalizeUser(user)
            val result = getConfigImpl(user, experimentName)
            logConfigImpl(normalizedUser, experimentName, result)
            return@captureSync getDynamicConfigFromEvalResult(result, experimentName)
        }, {
            return@captureSync DynamicConfig.empty(experimentName)
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
            return@capture getDynamicConfigFromEvalResult(result, experimentName)
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
                    return@capture getDynamicConfigFromEvalResult(result, expName)
                }
            }
            for (expName in experiments) {
                if (configEvaluator.isUserAllocatedToExperiment(user, expName)) {
                    val result = getConfigImpl(normalizedUser, expName)
                    if (!disableExposure) {
                        logConfigImpl(normalizedUser, expName, result)
                    }
                    return@capture getDynamicConfigFromEvalResult(result, expName)
                }
            }
            // User is not allocated to any experiment at this point
            return@capture DynamicConfig.empty()
        }, {
            return@capture DynamicConfig.empty()
        }, configName = layerName)
    }

    override suspend fun getLayer(user: StatsigUser, layerName: String): Layer {
        if (!isSDKInitialized()) {
            return Layer.empty(layerName)
        }
        return this.errorBoundary.capture("getLayer", {
            return@capture getLayerImpl(user, layerName, false)
        }, {
            return@capture Layer.empty(layerName)
        }, configName = layerName)
    }

    override fun getLayerSync(user: StatsigUser, layerName: String, option: GetLayerOptions?): Layer {
        if (!isSDKInitialized()) {
            return Layer.empty(layerName)
        }
        return this.errorBoundary.captureSync("getLayerSync", {
            val disableExposureLogging = option?.disableExposureLogging == true
            return@captureSync getLayerImpl(user, layerName, disableExposureLogging)
        }, {
            return@captureSync Layer.empty(layerName)
        }, configName = layerName)
    }

    override fun getLayerSync(user: StatsigUser, layerName: String): Layer {
        if (!isSDKInitialized()) {
            return Layer.empty(layerName)
        }
        return this.errorBoundary.captureSync("getLayerSync", {
            return@captureSync getLayerImpl(user, layerName, false)
        }, {
            return@captureSync Layer.empty(layerName)
        }, configName = layerName)
    }

    override suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer {
        if (!isSDKInitialized()) {
            return Layer.empty(layerName)
        }
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
        if (!isSDKInitialized()) {
            return emptyMap()
        }
        var markerID: String? = null
        return this.errorBoundary.captureSync("getClientInitializeResponse", {
            markerID = diagnostics.markStart(KeyType.GET_CLIENT_INITIALIZE_RESPONSE, StepType.PROCESS, ContextType.GET_CLIENT_INITIALIZE_RESPONSE)
            val normalizedUser = normalizeUser(user)
            val response = configEvaluator.getClientInitializeResponse(normalizedUser, hash, clientSDKKey)
            diagnostics.markEnd(
                KeyType.GET_CLIENT_INITIALIZE_RESPONSE,
                !response.isEmpty(),
                StepType.PROCESS,
                ContextType.GET_CLIENT_INITIALIZE_RESPONSE,
                Marker(markerID = markerID),
            )
            return@captureSync response.toMap()
        }, {
            diagnostics.markEnd(
                KeyType.GET_CLIENT_INITIALIZE_RESPONSE,
                false,
                StepType.PROCESS,
                ContextType.GET_CLIENT_INITIALIZE_RESPONSE,
                Marker(markerID = markerID),
            )
            return@captureSync emptyMap()
        })
    }

    override fun overrideLayer(layerName: String, value: Map<String, Any>) {
        if (!isSDKInitialized()) {
            return
        }
        this.errorBoundary.captureSync("overrideLayer", {
            isSDKInitialized()
            configEvaluator.overrideLayer(layerName, value)
        }, { return@captureSync })
    }
    override fun removeLayerOverride(layerName: String) {
        if (!isSDKInitialized()) {
            return
        }
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
        if (!isSDKInitialized()) {
            return
        }
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
        if (!isSDKInitialized()) {
            return
        }
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
        if (!isSDKInitialized()) {
            return
        }
        errorBoundary.captureSync("overrideGate", {
            isSDKInitialized()
            configEvaluator.overrideGate(gateName, gateValue)
        }, { return@captureSync })
    }

    override fun removeGateOverride(gateName: String) {
        if (!isSDKInitialized()) {
            return
        }
        errorBoundary.captureSync("removeGateOverride", {
            isSDKInitialized()
            configEvaluator.removeGateOverride(gateName)
        }, { return@captureSync })
    }

    override fun overrideConfig(configName: String, configValue: Map<String, Any>) {
        if (!isSDKInitialized()) {
            return
        }
        errorBoundary.captureSync("overrideConfig", {
            isSDKInitialized()
            configEvaluator.overrideConfig(configName, configValue)
        }, { return@captureSync })
    }

    override fun removeConfigOverride(configName: String) {
        if (!isSDKInitialized()) {
            return
        }
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
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(false)
        }
        return statsigScope.future {
            return@future checkGate(user, gateName)
        }
    }

    override fun checkGateWithExposureLoggingDisabledAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(false)
        }
        return statsigScope.future {
            return@future checkGateWithExposureLoggingDisabled(user, gateName)
        }
    }

    override fun getConfigAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(DynamicConfig.empty(dynamicConfigName))
        }
        return statsigScope.future {
            return@future getConfig(user, dynamicConfigName)
        }
    }

    override fun getConfigWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        dynamicConfigName: String,
    ): CompletableFuture<DynamicConfig> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(DynamicConfig.empty())
        }
        return statsigScope.future {
            return@future getConfigWithExposureLoggingDisabled(user, dynamicConfigName)
        }
    }

    override fun getExperimentAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(DynamicConfig.empty(experimentName))
        }
        return statsigScope.future {
            return@future getExperiment(user, experimentName)
        }
    }

    override fun getExperimentWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        experimentName: String,
    ): CompletableFuture<DynamicConfig> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(DynamicConfig.empty())
        }
        return statsigScope.future {
            return@future getExperimentWithExposureLoggingDisabled(user, experimentName)
        }
    }

    override fun getExperimentInLayerForUserAsync(
        user: StatsigUser,
        layerName: String,
        disableExposure: Boolean,
    ): CompletableFuture<DynamicConfig> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(DynamicConfig.empty())
        }
        return statsigScope.future {
            return@future getExperimentInLayerForUser(user, layerName, disableExposure)
        }
    }

    override fun getLayerAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(Layer.empty(layerName))
        }
        return statsigScope.future {
            return@future getLayer(user, layerName)
        }
    }

    override fun getLayerWithExposureLoggingDisabledAsync(
        user: StatsigUser,
        layerName: String,
    ): CompletableFuture<Layer> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(Layer.empty(layerName))
        }
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
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(null)
        }
        return statsigScope.future {
            manuallyLogLayerParameterExposure(user, layerName, paramName)
        }.thenApply { return@thenApply null }
    }

    override fun manuallyLogGateExposureAsync(user: StatsigUser, gateName: String): CompletableFuture<Void> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(null)
        }
        return statsigScope.future {
            manuallyLogGateExposure(user, gateName)
        }.thenApply { return@thenApply null }
    }

    override fun manuallyLogConfigExposureAsync(user: StatsigUser, configName: String): CompletableFuture<Void> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(null)
        }
        return statsigScope.future {
            manuallyLogConfigExposure(user, configName)
        }.thenApply { return@thenApply null }
    }

    override fun manuallyLogExperimentExposureAsync(user: StatsigUser, experimentName: String): CompletableFuture<Void> {
        if (!isSDKInitialized()) {
            return CompletableFuture.completedFuture(null)
        }
        return statsigScope.future {
            manuallyLogConfigExposure(user, experimentName)
        }.thenApply { return@thenApply null }
    }

    /**
     *
     * @deprecated
     * - we make no promises of support for this API
     */
    override fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>> {
        if (!isSDKInitialized()) {
            return mapOf()
        }
        return configEvaluator.getVariants(experimentName)
    }

    override fun shutdown() {
        if (!isSDKInitialized()) {
            return
        }
        runBlocking { shutdownSuspend() }
    }

    override suspend fun flush() {
        if (!isSDKInitialized()) {
            return
        }
        logger.flush()
    }

    override fun getCustomLogger(): LoggerInterface {
        return options.customLogger
    }

    private fun getLayerImpl(user: StatsigUser, layerName: String, disableExposure: Boolean, onExposure: OnLayerExposure? = null): Layer {
        if (!isSDKInitialized()) {
            return Layer.empty(layerName)
        }
        return this.errorBoundary.captureSync("getLayerImpl", {
            val normalizedUser = normalizeUser(user)

            val result: ConfigEvaluation = configEvaluator.getLayer(normalizedUser, layerName)

            val value = (result.jsonValue as? Map<*, *>) ?: mapOf<String, Any>()

            return@captureSync Layer(
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
            return@captureSync Layer.empty(layerName)
        })
    }

    override suspend fun manuallyLogLayerParameterExposure(
        user: StatsigUser,
        layerName: String,
        paramName: String,
    ) {
        val normalizedUser = normalizeUser(user)
        var result: ConfigEvaluation = configEvaluator.getLayer(normalizedUser, layerName)
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
        if (!isInitialized()) { // for multi-instance, if the server has not been initialized
            getCustomLogger().warning("Call and wait for initialize StatsigServer to complete before calling SDK methods.")
            return false
        }
        if (statsigJob.isCancelled || statsigJob.isCompleted) {
            options.customLogger.info("StatsigServer was shutdown")
            return false
        }
        if (!this::configEvaluator.isInitialized || !configEvaluator.isInitialized) { // If the server was never initialized
            options.customLogger.warning("Must initialize a server before calling other APIs")
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

    private fun getDynamicConfigFromEvalResult(
        result: ConfigEvaluation,
        configName: String,
    ): DynamicConfig {
        return DynamicConfig(
            configName,
            (result.jsonValue ?: emptyMap<String, Any>()) as Map<String, Any>,
            result.ruleID,
            result.groupName,
            result.secondaryExposures,
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
            server.getCustomLogger().info("[Statsig]: Shutting down Statsig because of unhandled exception from your server")
            server.shutdown()
            throw e
        }
    }
}
