package com.statsig.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture

class Statsig {
    companion object {

        private val statsigSingletonJob = SupervisorJob()
        private val statsigSingletonScope = CoroutineScope(statsigSingletonJob)

        @Volatile
        private lateinit var statsigServer: StatsigServer

        suspend fun initialize(
            serverSecret: String,
            options: StatsigOptions,
        ) {
            if (!::statsigServer.isInitialized) { // Quick check without synchronization
                synchronized(this) {
                    if (!::statsigServer.isInitialized) { // Secondary check in case another thread already created the default server
                        statsigServer = StatsigServer.createServer(serverSecret, options)
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

        suspend fun shutdownSuspend() {
            statsigServer.shutdownSuspend()
        }

        @JvmStatic
        fun logEvent(user: StatsigUser?, eventName: String, value: String?, metadata: Map<String, String>?) {
            enforceInitialized()
            statsigServer.logEvent(user, eventName, value, metadata)
        }

        @JvmStatic
        fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>?) {
            enforceInitialized()
            statsigServer.logEvent(user, eventName, value, metadata)
        }

        @JvmStatic
        fun initializeAsync(
            serverSecret: String,
            options: StatsigOptions,
        ): CompletableFuture<Unit> {
            if (!::statsigServer.isInitialized) { // Quick check without synchronization
                synchronized(this) {
                    if (!::statsigServer.isInitialized) { // Secondary check in case another thread already created the default server
                        statsigServer = StatsigServer.createServer(serverSecret, options)
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
        fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getConfigAsync(user, dynamicConfigName)
        }

        @JvmStatic
        fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> {
            enforceInitialized()
            return statsigServer.getExperimentAsync(user, experimentName)
        }

        @JvmStatic
        fun shutdown() {
            runBlocking { statsigServer.shutdown() }
        }

        @JvmStatic
        fun shutdownAsync(): CompletableFuture<Unit> {
            enforceInitialized()
            return statsigServer.shutdownAsync()
        }

        private fun enforceInitialized() {
            assert(::statsigServer.isInitialized) { "You must call 'initialize()' before using Statsig" }
        }
    }
}
