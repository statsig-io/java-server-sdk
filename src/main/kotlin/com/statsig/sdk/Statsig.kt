package com.statsig.sdk

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking

class Statsig {
    companion object {

        @Volatile private lateinit var statsigServer: StatsigServer

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

        suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
            enforceInitialized()
            return statsigServer.checkGate(user, gateName)
        }

        suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
            enforceInitialized()
            return statsigServer.getConfig(user, dynamicConfigName)
        }

        suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
            enforceInitialized()
            return statsigServer.getExperiment(user, experimentName)
        }

        suspend fun getExperimentWithExposureLoggingDisabled(
                user: StatsigUser,
                experimentName: String
        ): DynamicConfig {
            enforceInitialized()
            return statsigServer.getExperimentWithExposureLoggingDisabled(user, experimentName)
        }

        suspend fun getExperimentInLayerForUser(
                user: StatsigUser,
                layerName: String,
                disableExposure: Boolean = false
        ): DynamicConfig {
            enforceInitialized()
            return statsigServer.getExperimentInLayerForUser(user, layerName, disableExposure)
        }

        suspend fun getLayer(user: StatsigUser, layerName: String): Layer {
            enforceInitialized()
            return statsigServer.getLayer(user, layerName)
        }

        suspend fun shutdownSuspend() {
            statsigServer.shutdownSuspend()
        }

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

        @JvmStatic
        fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
            enforceInitialized()
            return statsigServer.checkGateAsync(user, gateName)
        }

        @JvmStatic
        fun getConfigAsync(
                user: StatsigUser,
                dynamicConfigName: String
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getConfigAsync(user, dynamicConfigName)
        }

        @JvmStatic
        fun getExperimentAsync(
                user: StatsigUser,
                experimentName: String
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getExperimentAsync(user, experimentName)
        }

        @JvmStatic
        fun getExperimentWithExposureLoggingDisabledAsync(
                user: StatsigUser,
                experimentName: String
        ): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getExperimentWithExposureLoggingDisabledAsync(user, experimentName)
        }

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
         * - we make no promises of support for this API
         */
        @JvmStatic
        fun _getExperimentGroups(experimentName: String): Map<String, Map<String, Any>> {
            enforceInitialized()
            return statsigServer._getExperimentGroups(experimentName)
        }

        @JvmStatic
        fun shutdown() {
            runBlocking { statsigServer.shutdown() }
        }

        private fun enforceInitialized() {
            assert(::statsigServer.isInitialized) {
                "You must call 'initialize()' before using Statsig"
            }
        }
    }
}
