package com.statsig.sdk

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

class StatsigServer {
    companion object {

        private lateinit var serverDriver: ServerDriver

        suspend fun initialize(serverSecret: String, options: StatsigOptions = StatsigOptions()) {
            if (this::serverDriver.isInitialized) {
                return
            }
            serverDriver = ServerDriver(serverSecret, options)
            serverDriver.initialize()
        }

        suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
            return serverDriver.checkGate(user, gateName)
        }

        suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
            return serverDriver.getConfig(user, dynamicConfigName)
        }

        suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
            return serverDriver.getExperiment(user, experimentName)
        }

        @JvmStatic
        @JvmOverloads
        fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>? = null) {
            serverDriver.logEvent(user, eventName, value, metadata)
        }

        @JvmStatic
        @JvmOverloads
        fun logEvent(user: StatsigUser?, eventName: String, value: String? = null, metadata: Map<String, String>? = null) {
            serverDriver.logEvent(user, eventName, value, metadata)
        }

        @JvmStatic
        fun shutdown() {
            serverDriver.shutdown()
        }

        @JvmStatic
        @JvmOverloads
        fun initializeAsync(serverSecret: String, options: StatsigOptions = StatsigOptions()): CompletableFuture<Unit> = GlobalScope.future {
            initialize(serverSecret, options)
        }

        @JvmStatic
        fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> = GlobalScope.future {
            return@future checkGate(user, gateName)
        }

        @JvmStatic
        fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig> = GlobalScope.future {
            return@future getConfig(user, dynamicConfigName)
        }

        @JvmStatic
        fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> = GlobalScope.future {
            return@future getExperiment(user, experimentName)
        }
    }
}