package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture

class Statsig {
    companion object {

        @Volatile internal lateinit var statsigServer: StatsigServer

        /**
         * Initializes the Statsig SDK.
         *
         * @param serverSecret The server SDK key copied from console.statsig.com
         * @param options The StatsigOptions object used to configure the SDK
         */
        suspend fun initialize(
            serverSecret: String,
            options: StatsigOptions,
        ) {
            if (!::statsigServer.isInitialized) { // Quick check without synchronization
                synchronized(this) {
                    if (!::statsigServer.isInitialized
                    ) { // Secondary check in case another thread already created the default server
                        statsigServer = StatsigServer.create(serverSecret, options)
                    }
                }
                statsigServer.initialize()
            }
        }

        /**
         * Get the boolean result of a gate, evaluated against a given user.
         * Exposure event will automatically be logged for the gate.
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         */
        suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
            enforceInitialized()
            return statsigServer.checkGate(user, gateName)
        }

        /**
         * Get the values of a dynamic config, evaluated against the given user.
         * An exposure event will automatically be logged for the dynamic config.
         *
         * @param user A StatsigUser object used for evaluation
         * @param dynamicConfigName The name of the dynamic config
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
            enforceInitialized()
            return statsigServer.getConfig(user, dynamicConfigName)
        }

        /**
         * Get the values of an experiment, evaluated against the given user.
         * An exposure event will automatically be logged for the experiment.
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser
         */
        suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
            enforceInitialized()
            return statsigServer.getExperiment(user, experimentName)
        }

        /**
         * Get the values of an experiment, evaluated against the given user.
         * Exposure events will not be fired on resulting DynamicConfig class.
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser with event exposers disabled
         */
        suspend fun getExperimentWithExposureLoggingDisabled(
            user: StatsigUser,
            experimentName: String
        ): DynamicConfig {
            enforceInitialized()
            return statsigServer.getExperimentWithExposureLoggingDisabled(user, experimentName)
        }

        /**
         * Get the values of a layer, evaluated against the given user.
         * Exposure events will be fired when get is called on resulting Layer class.
         *
         * @param user A StatsigUser object used for the evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser
         */
        suspend fun getLayer(user: StatsigUser, layerName: String): Layer {
            enforceInitialized()
            return statsigServer.getLayer(user, layerName)
        }

        /**
         * Get the values of a layer, evaluated against the given user.
         * Exposure events will not be fired on resulting Layer class.
         *
         * @param user A StatsigUser object used for evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser with event exposers disabled
         */
        suspend fun getLayerWithExposureLoggingDisabled(user: StatsigUser, layerName: String): Layer {
            enforceInitialized()
            return statsigServer.getLayerWithExposureLoggingDisabled(user, layerName)
        }

        // TODO
        suspend fun shutdownSuspend() {
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
            enforceInitialized()
            statsigServer.overrideGate(gateName, gateValue)
        }

        /**
         * Sets a value to be returned for the given dynamic config/experiment instead of the actual evaluated value.
         *
         * @param configName The name of the dynamic config or experiment to be overriden
         * @param configValue The value that will be returned
         */
        @JvmStatic
        fun overrideConfig(configName: String, configValue: Map<String, Any>) {
            enforceInitialized()
            statsigServer.overrideConfig(configName, configValue)
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
            metadata: Map<String, String>? = null
        ) {
            enforceInitialized()
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
            metadata: Map<String, String>? = null
        ) {
            enforceInitialized()
            statsigServer.logEvent(user, eventName, value, metadata)
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
        ): CompletableFuture<Unit> {
            if (!::statsigServer.isInitialized) { // Quick check without synchronization
                synchronized(this) {
                    if (!::statsigServer.isInitialized
                    ) { // Secondary check in case another thread already created the default server
                        statsigServer = StatsigServer.create(serverSecret, options)
                    }
                }
                return statsigServer.initializeAsync()
            }
            return CompletableFuture.completedFuture(Unit)
        }

        /**
         * Asynchronously get the boolean result of a gate, evaluated against a given user.
         * Exposure event will automatically be logged for the gate.
         * (Java compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param gateName The name of the gate being evaluated
         */
        @JvmStatic
        fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
            enforceInitialized()
            return statsigServer.checkGateAsync(user, gateName)
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
            dynamicConfigName: String
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getConfigAsync(user, dynamicConfigName)
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
            experimentName: String
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getExperimentAsync(user, experimentName)
        }

        /**
         * Asynchronously get the values of an experiment, evaluated against the given user.
         * Exposure events will not be fired on resulting DynamicConfig class.
         * (Java Compatible)
         *
         * @param user A StatsigUser object used for the evaluation
         * @param experimentName The name of the experiment
         * @return DynamicConfig object evaluated for the selected StatsigUser with event exposers disabled
         */
        @JvmStatic
        fun getExperimentWithExposureLoggingDisabledAsync(
            user: StatsigUser,
            experimentName: String
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getExperimentWithExposureLoggingDisabledAsync(user, experimentName)
        }

        /**
         * Asynchronously get the values of a layer, evaluated against the given user.
         * Exposure events will be fired when get is called on resulting Layer class.
         * (Java Compatible)
         *
         * @param user A StatsigUser object used for the evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser
         */
        @JvmStatic
        fun getLayerAsync(
            user: StatsigUser,
            layerName: String
        ): CompletableFuture<Layer> {
            enforceInitialized()
            return statsigServer.getLayerAsync(user, layerName)
        }

        @JvmStatic
        fun getLayerWithCustomExposureLoggingAsync(
            user: StatsigUser,
            layerName: String,
            onExposure: OnLayerExposure
        ): CompletableFuture<Layer> {
            enforceInitialized()
            return statsigServer.getLayerWithCustomExposureLoggingAsync(user, layerName, onExposure)
        }

        /**
         * Asynchronously get the values of a layer, evaluated against the given user.
         * Exposure events will not be fired on resulting Layer class.
         * (Java Compatible)
         *
         * @param user A StatsigUser object used for evaluation
         * @param layerName The name of the layer
         * @return Layer object evaluated for the selected StatsigUser with event exposers disabled
         */
        @JvmStatic
        fun getLayerWithExposureLoggingDisabledAsync(
            user: StatsigUser,
            layerName: String,
        ): CompletableFuture<Layer> {
            enforceInitialized()
            return statsigServer.getLayerWithExposureLoggingDisabledAsync(user, layerName)
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
            disableExposure: Boolean
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getExperimentInLayerForUserAsync(user, layerName, disableExposure)
        }

        /**
         * @deprecated
         * we make no promises of support for this API
         * Asynchronously get experiment groups for the selected Experiment.
         *
         * @param experimentName The name of the experiment
         * @return A Map of the selected experiment's groups
         */
        @JvmStatic
        fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>> {
            enforceInitialized()
            return statsigServer._getExperimentGroups(experimentName)
        }

        /**
         * Stops all Statsig activity and flushes any pending events.
         */
        @JvmStatic
        fun shutdown() {
            runBlocking { statsigServer.shutdown() }
        }

        /**
         * Ensure initialize has been called.
         */
        private fun enforceInitialized() {
            assert(::statsigServer.isInitialized) {
                "You must call 'initialize()' before using Statsig"
            }
        }
    }
}
