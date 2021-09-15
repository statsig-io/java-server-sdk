package com.statsig.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

object StatsigServer {

    private lateinit var serverDriver: ServerDriver
    private val statsigServerJob = SupervisorJob()
    private val statsigCoroutineScope = CoroutineScope(statsigServerJob)

    @JvmSynthetic // Hide from Java
    suspend fun initialize(serverSecret: String, options: StatsigOptions = StatsigOptions()) {
        if (this::serverDriver.isInitialized) {
            return
        }
        serverDriver = ServerDriver(serverSecret, options, statsigCoroutineScope)
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

    @JvmSynthetic
    suspend fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>? = null) {
        serverDriver.logEvent(user, eventName, value, metadata)
    }

    @JvmSynthetic
    suspend fun logEvent(user: StatsigUser?, eventName: String, value: String? = null, metadata: Map<String, String>? = null) {
        serverDriver.logEvent(user, eventName, value, metadata)
    }

    @JvmSynthetic
    suspend fun shutdown() {
        serverDriver.shutdown()
    }

    @JvmStatic
    fun shutdownSync() {
        runBlocking {
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

    @JvmStatic
    @JvmOverloads
    fun logEventAsync(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>? = null): CompletableFuture<Unit> = statsigCoroutineScope.future {
        return@future logEvent(user, eventName, value, metadata)
    }

    @JvmStatic
    @JvmOverloads
    fun logEventAsync(user: StatsigUser?, eventName: String, value: String? = null, metadata: Map<String, String>? = null): CompletableFuture<Unit> = statsigCoroutineScope.future {
        return@future logEvent(user, eventName, value, metadata)
    }
}
