package com.statsig.sdk

import com.statsig.sdk.persistent_storage.UserPersistedValues
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture

class Statsig {
    companion object {

        @Volatile internal lateinit var statsigServer: StatsigServer

        /**
         * Initializes the Statsig SDK.
         * @return Details of initializaiton, success
         * @param serverSecret The server SDK key copied from the project's API Keys
         * @param options The StatsigOptions object used to configure the SDK
         */
        suspend fun initialize(
            serverSecret: String,
            options: StatsigOptions,
        ): InitializationDetails? {
            if (!isInitialized()) { // Quick check without synchronization
                synchronized(this) {
                    if (!isInitialized()
                    ) { // Secondary check in case another thread already created the default server
                        statsigServer = StatsigServer.create()
                    }
                }
                return statsigServer.initialize(serverSecret, options)
            }

            return null
        }

        /**
         * @deprecated Please use checkGateSync instead.
         * @see https://docs.statsig.com/server/deprecation-notices
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         */
        suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
            if (!checkInitialized()) {
                return false
            }
            return statsigServer.checkGate(user, gateName)
        }

        /**
         * Get the boolean result of a gate synchronously, evaluated against a given user.
         * An exposure event will automatically be logged for the gate.
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         * @param option advanced setup for checkGate, for example disable exposure logging
         */
        @JvmStatic
        @JvmOverloads
        fun checkGateSync(user: StatsigUser, gateName: String, option: CheckGateOptions? = null): Boolean {
            if (!checkInitialized()) {
                return false
            }
            return statsigServer.checkGateSync(user, gateName, option)
        }

        /**
         * Get the boolean result of a gate, evaluated against a given user.
         * Does not trigger an exposure event.
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         */
        suspend fun checkGateWithExposureLoggingDisabled(user: StatsigUser, gateName: String): Boolean {
            if (!checkInitialized()) {
                return false
            }
            return statsigServer.checkGateWithExposureLoggingDisabled(user, gateName)
        }

        /**
         * @deprecated Please use getConfigSync instead.
         * @see https://docs.statsig.com/server/deprecation-notices
         *
         * @param user A StatsigUser object used for evaluation
         * @param dynamicConfigName The name of the DynamicConfig
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
            if (!checkInitialized()) {
                return DynamicConfig.empty(dynamicConfigName)
            }
            return statsigServer.getConfig(user, dynamicConfigName)
        }

        /**
         * Get the values of a DynamicConfig synchronously, evaluated against the given user.
         * An exposure event will automatically be logged for the DynamicConfig.
         *
         * @param user A StatsigUser object used for evaluation
         * @param dynamicConfigName The name of the DynamicConfig
         * @param option advanced setup for getConfig, for example disable exposure logging
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        @JvmStatic
        @JvmOverloads
        fun getConfigSync(user: StatsigUser, dynamicConfigName: String, option: GetConfigOptions? = null): DynamicConfig {
            if (!checkInitialized()) {
                return DynamicConfig.empty(dynamicConfigName)
            }
            return statsigServer.getConfigSync(user, dynamicConfigName, option)
        }

        /**
         * Get the values of a DynamicConfig, evaluated against the given user.
         * Does not trigger an exposure event.
         *
         * @param user A StatsigUser object used for evaluation
         * @param dynamicConfigName The name of the DynamicConfig
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        suspend fun getConfigWithExposureLoggingDisabled(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
            if (!checkInitialized()) {
                return DynamicConfig.empty(dynamicConfigName)
            }
            return statsigServer.getConfigWithExposureLoggingDisabled(user, dynamicConfigName)
        }

        /**
         * Manually log a config exposure event to Statsig.
         *
         * @param user A StatsigUser object to be included in the log
         * @param configName The name of the config to be logged
         */
        suspend fun manuallyLogConfigExposure(user: StatsigUser, configName: String) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.manuallyLogConfigExposure(user, configName)
        }

        /**
         * @deprecated Please use getExperimentSync instead.
         * @see https://docs.statsig.com/server/deprecation-notices
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
            if (!checkInitialized()) {
                return DynamicConfig.empty(experimentName)
            }
            return statsigServer.getExperiment(user, experimentName)
        }

        /**
         * Get the values of an experiment synchronously, evaluated against the given user.
         * An exposure event will automatically be logged for the experiment.
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @param option advanced setup for getExperiment, for example disable exposure logging
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        @JvmStatic
        @JvmOverloads
        fun getExperimentSync(user: StatsigUser, experimentName: String, option: GetExperimentOptions? = null): DynamicConfig {
            if (!checkInitialized()) {
                return DynamicConfig.empty(experimentName)
            }
            return statsigServer.getExperimentSync(user, experimentName, option)
        }

        /**
         * Get the values of an experiment, evaluated against the given user.
         * Does not trigger an exposure event.
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        suspend fun getExperimentWithExposureLoggingDisabled(
            user: StatsigUser,
            experimentName: String,
        ): DynamicConfig {
            if (!checkInitialized()) {
                return DynamicConfig.empty(experimentName)
            }
            return statsigServer.getExperimentWithExposureLoggingDisabled(user, experimentName)
        }

        /**
         * @deprecated Please use getLayerSync instead.
         * @see https://docs.statsig.com/server/deprecation-notices
         *
         * @param user A StatsigUser object used for the evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser
         */
        suspend fun getLayer(user: StatsigUser, layerName: String): Layer {
            if (!checkInitialized()) {
                return Layer.empty(layerName)
            }
            return statsigServer.getLayer(user, layerName)
        }

        /**
         * Get the values of a layer synchronously, evaluated against the given user.
         * Exposure events will be fired when get is called on resulting Layer object.
         *
         * @param user A StatsigUser object used for the evaluation
         * @param layerName The name of the layer
         * @param option advanced setup for getLayer, for example disable exposure logging
         * @return Layer object evaluated for the selected StatsigUser
         */
        @JvmStatic
        @JvmOverloads
        fun getLayerSync(user: StatsigUser, layerName: String, option: GetLayerOptions? = null): Layer {
            if (!checkInitialized()) {
                return Layer.empty(layerName)
            }
            return statsigServer.getLayerSync(user, layerName, option)
        }

        /**
         * Get the values of a layer, evaluated against the given user.
         * Exposure events will not be fired on resulting Layer object.
         *
         * @param user A StatsigUser object used for evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser
         */
        suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer {
            if (!checkInitialized()) {
                return Layer.empty(layerName)
            }
            return statsigServer.getLayerWithExposureLoggingDisabled(user, layerName)
        }

        /**
         * Manually log a layer exposure event to Statsig.
         *
         * @param user A StatsigUser object to be included in the log
         * @param layerName The name of the layer to be logged
         * @param paramName The name of the parameter that was exposed
         */
        suspend fun manuallyLogLayerParameterExposure(user: StatsigUser, layerName: String, paramName: String) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.manuallyLogLayerParameterExposure(user, layerName, paramName)
        }

        /**
         * Stores a local layer override
         *
         * @param layerName the layer to override
         * @param value the json value to override the config to
         */
        @JvmStatic
        fun overrideLayer(layerName: String, value: Map<String, Any>) {
            if (checkInitialized()) {
                statsigServer.overrideLayer(layerName, value)
            }
        }

        /**
         * Removes the given layer override
         *
         * @param layerName
         */
        @JvmStatic
        fun removeLayerOverride(layerName: String) {
            if (checkInitialized()) {
                statsigServer.removeLayerOverride(layerName)
            }
        }

        /**
         * Stops all Statsig activity and flushes any pending events.
         */
        suspend fun shutdownSuspend() {
            if (!checkInitialized()) {
                return
            }
            statsigServer.shutdownSuspend()
        }

        /**
         * Sets a value to be returned for the given gate instead of the actual evaluated value.
         *
         * @param gateName The name of the gate to be overridden
         * @param gateValue The value that will be returned
         */
        @JvmStatic
        fun overrideGate(gateName: String, gateValue: Boolean) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.overrideGate(gateName, gateValue)
        }

        /**
         * Removes the given gate override
         *
         * @param gateName
         */
        @JvmStatic
        fun removeGateOverride(gateName: String) {
            if (checkInitialized()) {
                statsigServer.removeGateOverride(gateName)
            }
        }

        /**
         * Sets a value to be returned for the given dynamic config/experiment instead of the actual evaluated value.
         *
         * @param configName The name of the dynamic config or experiment to be overridden
         * @param configValue The value that will be returned
         */
        @JvmStatic
        fun overrideConfig(configName: String, configValue: Map<String, Any>) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.overrideConfig(configName, configValue)
        }

        /**
         * Removes the given config override
         *
         * @param configName
         */
        @JvmStatic
        fun removeConfigOverride(configName: String) {
            if (checkInitialized()) {
                statsigServer.removeConfigOverride(configName)
            }
        }

        /**
         * Load persisted values for given user and idType used for evaluation
         *
         * @param user The StatsigUser object used for evaluation
         * @param idType The ID type
         *
         * @return A map of persisted evaluations for a given user
         */
        @JvmStatic
        suspend fun getUserPersistedValues(
            user: StatsigUser,
            idType: String,
        ): UserPersistedValues {
            if (!checkInitialized()) {
                return emptyMap()
            }
            return statsigServer.getUserPersistedValues(user, idType)
        }

        /**
         * Gets all evaluated values for the given user.
         * These values can then be given to a Statsig Client SDK via bootstrapping.
         * Note: See Java SDK documentation https://docs.statsig.com/server/javaSDK
         *
         * @param user The StatsigUser object used for evaluation
         * @param hash The hashing algorithm to use when obfuscating names
         *
         * @return An initialize response containing evaluated gates/configs/layers
         */
        @JvmStatic
        fun getClientInitializeResponse(
            user: StatsigUser,
            hash: HashAlgo = HashAlgo.SHA256,
            clientSDKKey: String? = null,
        ): Map<String, Any> {
            if (!checkInitialized()) {
                return emptyMap()
            }
            return statsigServer.getClientInitializeResponse(user, hash, clientSDKKey)
        }

        /**
         * Logs an event to Statsig with the provided values.
         *
         * @param user A StatsigUser object to be included in the log
         * @param eventName The name given to the event
         * @param value A top level value for the event
         * @param metadata Any extra values to be logged
         */
        @JvmStatic
        @JvmOverloads
        fun logEvent(
            user: StatsigUser?,
            eventName: String,
            value: String? = null,
            metadata: Map<String, String>? = null,
        ) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.logEvent(user, eventName, value, metadata)
        }

        /**
         * Logs an event to Statsig with the provided values.
         *
         * @param user A StatsigUser object to be included in the log
         * @param eventName The name given to the event
         * @param value A top level value for the event
         * @param metadata Any extra values to be logged
         */
        @JvmStatic
        @JvmOverloads
        fun logEvent(
            user: StatsigUser?,
            eventName: String,
            value: Double,
            metadata: Map<String, String>? = null,
        ) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.logEvent(user, eventName, value, metadata)
        }

        /**
         * Manually log a gate exposure event to Statsig.
         *
         * @param user A StatsigUser object to be included in the log
         * @param gateName The name of the gate to log
         */
        suspend fun manuallyLogGateExposure(
            user: StatsigUser,
            gateName: String,
        ) {
            if (!checkInitialized()) {
                return
            }
            statsigServer.manuallyLogGateExposure(user, gateName)
        }

        /**
         * Asynchronously initializes the Statsig SDK.
         * (Java compatible)
         *
         * @param serverSecret The server SDK key copied from console.statsig.com
         * @param options The StatsigOptions object used to configure the SDK
         */
        @JvmStatic
        @JvmOverloads
        fun initializeAsync(
            serverSecret: String,
            options: StatsigOptions = StatsigOptions(),
        ): CompletableFuture<InitializationDetails?> {
            if (!isInitialized()) { // Quick check without synchronization
                synchronized(this) {
                    if (!isInitialized()
                    ) { // Secondary check in case another thread already created the default server
                        statsigServer = StatsigServer.create()
                    }
                    return statsigServer.initializeAsync(serverSecret, options)
                }
            }
            return CompletableFuture.completedFuture(null)
        }

        /**
         * Asynchronously get the boolean result of a gate, evaluated against a given user.
         * An exposure event will automatically be logged for the gate.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         */
        @JvmStatic
        fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(false)
            }
            return statsigServer.checkGateAsync(user, gateName)
        }

        /**
         * Get the boolean result of a gate, evaluated against a given user.
         * Does not trigger an exposure event.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         */
        @JvmStatic
        fun checkGateWithExposureLoggingDisabledAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(false)
            }
            return statsigServer.checkGateWithExposureLoggingDisabledAsync(user, gateName)
        }

        /**
         * Get details of a gate, evaluated against a given user.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         * @param option advanced setup for getFeatureGate, for example disable exposure logging
         *
         * @return APIFeatureGate feature gate object contains evaluation details
         */
        @JvmStatic
        @JvmOverloads
        fun getFeatureGate(user: StatsigUser, gateName: String, option: GetFeatureGateOptions? = null): APIFeatureGate {
            if (!checkInitialized()) {
                return APIFeatureGate(gateName, false, null, arrayListOf(), EvaluationReason.UNINITIALIZED, null)
            }
            return statsigServer.getFeatureGate(user, gateName, option)
        }

        /**
         * Manually log a gate exposure event to Statsig.
         * (Java compatible)
         *
         * @param user A StatsigUser object to be included in the log
         * @param gateName The name of the gate to log
         */
        @JvmStatic
        fun manuallyLogGateExposureAsync(
            user: StatsigUser,
            gateName: String,
        ): CompletableFuture<Void> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(null)
            }
            return statsigServer.manuallyLogGateExposureAsync(user, gateName)
        }

        /**
         * Asynchronously get the values of a dynamic config, evaluated against the given user.
         * An exposure event will automatically be logged for the DynamicConfig.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param dynamicConfigName The name of the dynamic config
         */
        @JvmStatic
        fun getConfigAsync(
            user: StatsigUser,
            dynamicConfigName: String,
        ): CompletableFuture<DynamicConfig> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(DynamicConfig.empty(dynamicConfigName))
            }
            return statsigServer.getConfigAsync(user, dynamicConfigName)
        }

        /**
         * Asynchronously get the values of a dynamic config, evaluated against the given user.
         * Does not trigger an exposure event.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param dynamicConfigName The name of the dynamic config
         */
        @JvmStatic
        fun getConfigWithExposureLoggingDisabledAsync(
            user: StatsigUser,
            dynamicConfigName: String,
        ): CompletableFuture<DynamicConfig> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(DynamicConfig.empty(dynamicConfigName))
            }
            return statsigServer.getConfigWithExposureLoggingDisabledAsync(user, dynamicConfigName)
        }

        /**
         * Manually log a config exposure event to Statsig.
         * (Java compatible)
         *
         * @param user A StatsigUser object to be included in the log
         * @param configName The name of the config to be logged
         */
        @JvmStatic
        fun manuallyLogConfigExposureAsync(user: StatsigUser, configName: String): CompletableFuture<Void> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(null)
            }
            return statsigServer.manuallyLogConfigExposureAsync(user, configName)
        }

        /**
         * Asynchronously get the values of an experiment, evaluated against the given user.
         * An exposure event will automatically be logged for the experiment.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        @JvmStatic
        fun getExperimentAsync(
            user: StatsigUser,
            experimentName: String,
        ): CompletableFuture<DynamicConfig> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(DynamicConfig.empty(experimentName))
            }
            return statsigServer.getExperimentAsync(user, experimentName)
        }

        /**
         * Asynchronously get the values of an experiment, evaluated against the given user.
         * Does not trigger an exposure event.
         * (Java Compatible)
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        @JvmStatic
        fun getExperimentWithExposureLoggingDisabledAsync(
            user: StatsigUser,
            experimentName: String,
        ): CompletableFuture<DynamicConfig> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(DynamicConfig.empty(experimentName))
            }
            return statsigServer.getExperimentWithExposureLoggingDisabledAsync(user, experimentName)
        }

        /**
         * Manually log an experiment exposure event to Statsig.
         * (Java compatible)
         *
         * @param user A StatsigUser object to be included in the log
         * @param experimentName The name of the config to be logged
         */
        @JvmStatic
        fun manuallyLogExperimentExposureAsync(user: StatsigUser, experimentName: String): CompletableFuture<Void> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(null)
            }
            return statsigServer.manuallyLogConfigExposureAsync(user, experimentName)
        }

        /**
         * Asynchronously get the values of a layer, evaluated against the given user.
         * Exposure events will be fired when get is called on resulting Layer object.
         * (Java Compatible)
         *
         * @param user A StatsigUser object used for the evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser
         */
        @JvmStatic
        fun getLayerAsync(
            user: StatsigUser,
            layerName: String,
        ): CompletableFuture<Layer> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(Layer.empty(layerName))
            }
            return statsigServer.getLayerAsync(user, layerName)
        }

        /**
         * Asynchronously get the values of a layer, evaluated against the given user.
         * Exposure events will not be fired on resulting Layer object.
         * (Java Compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser
         */
        @JvmStatic
        fun getLayerWithExposureLoggingDisabledAsync(
            user: StatsigUser,
            layerName: String,
        ): CompletableFuture<Layer> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(Layer.empty(layerName))
            }
            return statsigServer.getLayerWithExposureLoggingDisabledAsync(user, layerName)
        }

        /**
         * Manually log a layer exposure event to Statsig.
         * (Java Compatible)
         *
         * @param user A StatsigUser object to be included in the log
         * @param layerName The name of the layer to be logged
         * @param paramName The name of the parameter that was exposed
         */
        @JvmStatic
        fun manuallyLogLayerParameterExposureAsync(user: StatsigUser, layerName: String, paramName: String): CompletableFuture<Void> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(null)
            }
            return statsigServer.manuallyLogLayerParameterExposureAsync(user, layerName, paramName)
        }

        /**
         * Asynchronously get the experiment the StatsigUser has been allocated to in the given experiment.
         * If the StatsigUser is not allocated to experiment in the given layer an empty DynamicConfig object is return.
         *
         * @param user A StatsigUser object used for evaluation
         * @param layerName The name of the layer
         * @param disableExposure should the resulting experiment trigger exposure events
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        @JvmStatic
        fun getExperimentInLayerForUserAsync(
            user: StatsigUser,
            layerName: String,
            disableExposure: Boolean,
        ): CompletableFuture<DynamicConfig> {
            if (!checkInitialized()) {
                return CompletableFuture.completedFuture(DynamicConfig.empty())
            }
            return statsigServer.getExperimentInLayerForUserAsync(user, layerName, disableExposure)
        }

        /**
         * Get experiment groups for the selected Experiment.
         *
         * @param experimentName The name of the experiment
         * @return A Map of the selected experiment's groups
         */
        @JvmStatic
        fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>> {
            if (!checkInitialized()) {
                return mapOf()
            }
            return statsigServer._getExperimentGroups(experimentName)
        }

        /**
         * Stops all Statsig activity and flushes any pending events.
         */
        @JvmStatic
        fun shutdown() {
            if (!checkInitialized()) {
                return
            }
            runBlocking { statsigServer.shutdown() }
        }

        @JvmStatic
        fun isInitialized(): Boolean {
            return ::statsigServer.isInitialized && statsigServer.initialized.get()
        }

        private fun checkInitialized(): Boolean {
            val initialized = isInitialized()
            if (!initialized) {
                if (::statsigServer.isInitialized) {
                    statsigServer.getCustomLogger().warning("Call and wait for initialize to complete before calling SDK methods.")
                } else {
                    println("Call and wait for initialize to complete before calling SDK methods.")
                }
            }
            return initialized
        }
    }
}
