package com.statsig.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

const val MAX_EVENTS: Int = 1000
const val FLUSH_TIMER_MS: Long = 60000

const val CONFIG_EXPOSURE_EVENT = "statsig::config_exposure"
const val LAYER_EXPOSURE_EVENT = "statsig::layer_exposure"
const val GATE_EXPOSURE_EVENT = "statsig::gate_exposure"

internal fun safeAddEvaluationToEvent(evaluationDetails: EvaluationDetails?, metadata: MutableMap<String, String>) {
    if (evaluationDetails == null) {
        return
    }

    metadata["reason"] = evaluationDetails.reason.toString()
    metadata["configSyncTime"] = evaluationDetails.configSyncTime.toString()
    metadata["initTime"] = evaluationDetails.initTime.toString()
    metadata["serverTime"] = evaluationDetails.serverTime.toString()
}

internal class StatsigLogger(
    private val coroutineScope: CoroutineScope,
    private val network: StatsigNetwork,
    private val statsigMetadata: Map<String, String>,
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val singleThreadDispatcher = executor.asCoroutineDispatcher()
    private var events = LockableArray<StatsigEvent>()
    private val timer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }

    fun log(event: StatsigEvent) {
        events.add(event)

        if (events.size() >= MAX_EVENTS) {
            coroutineScope.launch { flush() }
        }
    }

    fun logGateExposure(
        user: StatsigUser?,
        gateName: String,
        value: Boolean,
        ruleID: String,
        secondaryExposures: ArrayList<Map<String, String>>,
        isManualExposure: Boolean = false,
        evaluationDetails: EvaluationDetails?
    ) {
        val metadata = mutableMapOf(
            "gate" to gateName,
            "gateValue" to value.toString(),
            "ruleID" to ruleID,
            "isManualExposure" to isManualExposure.toString()
        )

        safeAddEvaluationToEvent(evaluationDetails, metadata)

        val event = StatsigEvent(
            GATE_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadata,
            secondaryExposures
        )
        log(event)
    }

    fun logConfigExposure(
        user: StatsigUser?,
        configName: String,
        ruleID: String,
        secondaryExposures: ArrayList<Map<String, String>>,
        isManualExposure: Boolean,
        evaluationDetails: EvaluationDetails?
    ) {
        val metadata =
            mutableMapOf("config" to configName, "ruleID" to ruleID, "isManualExposure" to isManualExposure.toString())
        safeAddEvaluationToEvent(evaluationDetails, metadata)

        val event = StatsigEvent(
            CONFIG_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadata,
            secondaryExposures
        )
        log(event)
    }

    fun logLayerExposure(
        user: StatsigUser?,
        layerExposureMetadata: LayerExposureMetadata,
        isManualExposure: Boolean = false
    ) {

        if (isManualExposure) {
            layerExposureMetadata.isManualExposure = "true"
        }
        val metadata = layerExposureMetadata.toStatsigEventMetadataMap()
        safeAddEvaluationToEvent(layerExposureMetadata.evaluationDetails, metadata)

        val event = StatsigEvent(
            LAYER_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadata,
            layerExposureMetadata.secondaryExposures
        )
        log(event)
    }

    suspend fun flush() {
        withContext(Dispatchers.IO) {
            if (events.size() == 0) {
                return@withContext
            }

            val flushEvents = events.reset()
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
