package com.statsig.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture

object StatsigServer {

    // Visible for testing
    internal lateinit var serverDriver: ServerDriver
    private val statsigServerJob = SupervisorJob()
    private val statsigCoroutineScope = CoroutineScope(statsigServerJob)

    @JvmSynthetic // Hide from Java
    suspend fun initialize(serverSecret: String, options: StatsigOptions = StatsigOptions()) {
        if (this::serverDriver.isInitialized) {
            return
        }
        serverDriver = ServerDriver(statsigCoroutineScope, serverSecret, options)
        serverDriver.initialize()
    }

    @JvmSynthetic
    suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        return serverDriver.checkGate(user, gateName)
    }

    @JvmSynthetic
    suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        return serverDriver.getConfig(user, dynamicConfigName)
    }

    @JvmSynthetic
    suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        return serverDriver.getExperiment(user, experimentName)
    }

    @JvmStatic
    @JvmOverloads
    fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>? = null) {
        statsigCoroutineScope.launch {
            serverDriver.logEvent(user, eventName, value, metadata)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun logEvent(user: StatsigUser?, eventName: String, value: String? = null, metadata: Map<String, String>? = null) {
        statsigCoroutineScope.launch {
            serverDriver.logEvent(user, eventName, value, metadata)
        }
    }

    @JvmStatic
    fun shutdown() {
        runBlocking { // Assuming this needs to be synchronous
            serverDriver.shutdown()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun initializeAsync(serverSecret: String, options: StatsigOptions = StatsigOptions()): CompletableFuture<Unit> = statsigCoroutineScope.future {
        initialize(serverSecret, options)
    }

    @JvmStatic
    fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> = statsigCoroutineScope.future {
        return@future checkGate(user, gateName)
    }

    @JvmStatic
    fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig> = statsigCoroutineScope.future {
        return@future getConfig(user, dynamicConfigName)
    }

    @JvmStatic
    fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> = statsigCoroutineScope.future {
        return@future getExperiment(user, experimentName)
    }
}
