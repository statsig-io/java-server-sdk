package com.statsig.sdk

import java.util.concurrent.CompletableFuture

object Statsig : StatsigServer {

    @Volatile
    private lateinit var statsigServer: StatsigServer

    @JvmStatic
    fun createDefault(serverSecret: String, options: StatsigOptions = StatsigOptions()): Statsig {
        if (!::statsigServer.isInitialized) { // Quick check without synchronization
            synchronized(this) {
                if (!::statsigServer.isInitialized) { // Secondary check in case another thread already created the default server
                    statsigServer = StatsigServer.createServer(serverSecret, options)
                }
            }
        }
        return this
    }

    override suspend fun initialize() {
        enforceDefaultCreated()
        statsigServer.initialize()
    }

    override suspend fun checkGate(user: StatsigUser, gateName: String): Boolean {
        enforceDefaultCreated()
        return statsigServer.checkGate(user, gateName)
    }

    override suspend fun getConfig(user: StatsigUser, dynamicConfigName: String): DynamicConfig {
        enforceDefaultCreated()
        return statsigServer.getConfig(user, dynamicConfigName)
    }

    override suspend fun getExperiment(user: StatsigUser, experimentName: String): DynamicConfig {
        enforceDefaultCreated()
        return statsigServer.getExperiment(user, experimentName)
    }

    override suspend fun shutdown() {
        shutdownSync()
    }

    override fun logEvent(user: StatsigUser?, eventName: String, value: String?, metadata: Map<String, String>?) {
        enforceDefaultCreated()
        statsigServer.logEvent(user, eventName, value, metadata)
    }

    override fun logEvent(user: StatsigUser?, eventName: String, value: Double, metadata: Map<String, String>?) {
        enforceDefaultCreated()
        statsigServer.logEvent(user, eventName, value, metadata)
    }

    override fun initializeAsync(): CompletableFuture<Unit> {
        enforceDefaultCreated()
        return statsigServer.initializeAsync()
    }

    override fun checkGateAsync(user: StatsigUser, gateName: String): CompletableFuture<Boolean> {
        enforceDefaultCreated()
        return statsigServer.checkGateAsync(user, gateName)
    }

    override fun getConfigAsync(user: StatsigUser, dynamicConfigName: String): CompletableFuture<DynamicConfig> {
        enforceDefaultCreated()
        return statsigServer.getConfigAsync(user, dynamicConfigName)
    }

    override fun getExperimentAsync(user: StatsigUser, experimentName: String): CompletableFuture<DynamicConfig> {
        enforceDefaultCreated()
        return statsigServer.getExperimentAsync(user, experimentName)
    }

    override fun shutdownSync() {
        throw IllegalAccessError("Shutdown of Statsig not allowed")
    }

    private fun enforceDefaultCreated() {
        assert(::statsigServer.isInitialized) { "You must call 'createDefault()' before using Statsig" }
    }
}