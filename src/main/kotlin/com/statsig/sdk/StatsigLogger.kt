package com.statsig.sdk

import kotlinx.coroutines.*
import java.util.concurrent.Executors

const val MAX_EVENTS: Int = 500
const val FLUSH_TIMER_MS: Long = 60000

const val CONFIG_EXPOSURE_EVENT = "statsig::config_exposure"
const val GATE_EXPOSURE_EVENT = "statsig::gate_exposure"

internal class StatsigLogger(
    coroutineScope: CoroutineScope,
    private val network: StatsigNetwork,
    private val statsigMetadata: Map<String, String>,
) {

    private val executor = Executors.newSingleThreadExecutor();
    private val singleThreadDispatcher = executor.asCoroutineDispatcher()
    private var events = arrayListOf<StatsigEvent>()
    private val timer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }

    suspend fun log(event: StatsigEvent) {
        withContext(singleThreadDispatcher) {
            events.add(event)

            if (events.size >= MAX_EVENTS) {
                flush()
                return@withContext
            }
        }
    }

    suspend fun logGateExposure(user: StatsigUser?, gateName: String, value: Boolean, ruleID: String,
                                secondaryExposures: ArrayList<Map<String, String>>) {
        val event = StatsigEvent(
            GATE_EXPOSURE_EVENT,
            eventValue = null,
            mapOf("gate" to gateName, "gateValue" to value.toString(), "ruleID" to ruleID),
            user,
            statsigMetadata,
            secondaryExposures
        )
        log(event)
    }

    suspend fun logConfigExposure(user: StatsigUser?, configName: String, ruleID: String,
                                  secondaryExposures: ArrayList<Map<String, String>>) {
        val event = StatsigEvent(
            CONFIG_EXPOSURE_EVENT,
            eventValue = null,
            mapOf("config" to configName, "ruleID" to ruleID),
            user,
            statsigMetadata,
            secondaryExposures
        )
        log(event)
    }

    suspend fun flush() {
        withContext(singleThreadDispatcher) {
            if (events.size == 0) {
                return@withContext
            }

            val flushEvents = events
            events = arrayListOf()

            network.postLogs(flushEvents, statsigMetadata)
        }
    }

    suspend fun shutdown() {
        timer.cancel()
        // Order matters!  Shutdown the executor after posting the final batch
        flush()
        executor.shutdown()
    }
}
