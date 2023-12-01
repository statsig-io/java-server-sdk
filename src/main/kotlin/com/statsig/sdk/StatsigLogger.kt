package com.statsig.sdk

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
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
const val DIAGNOSTICS_EVENT = "statsig::diagnostics"

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
    private val statsigMetadata: StatsigMetadata,
    private val statsigOptions: StatsigOptions,
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
    private val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    internal var diagnostics: Diagnostics? = null
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
        evaluationDetails: EvaluationDetails?,
    ) {
        val metadata = mutableMapOf(
            "gate" to gateName,
            "gateValue" to value.toString(),
            "ruleID" to ruleID,
            "isManualExposure" to isManualExposure.toString(),
        )

        safeAddEvaluationToEvent(evaluationDetails, metadata)

        val event = StatsigEvent(
            GATE_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadata,
            secondaryExposures,
        )
        log(event)
    }

    fun logConfigExposure(
        user: StatsigUser?,
        configName: String,
        ruleID: String,
        secondaryExposures: ArrayList<Map<String, String>>,
        isManualExposure: Boolean,
        evaluationDetails: EvaluationDetails?,
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
            secondaryExposures,
        )
        log(event)
    }

    fun logLayerExposure(
        user: StatsigUser?,
        layerExposureMetadata: LayerExposureMetadata,
        isManualExposure: Boolean = false,
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
            layerExposureMetadata.secondaryExposures,
        )
        log(event)
    }

    fun logDiagnostics(context: ContextType, markers: Collection<Marker>) {
        if (markers.isEmpty()) {
            return
        }
        val event = StatsigEvent(DIAGNOSTICS_EVENT)
        event.eventMetadata = mapOf(
            "context" to context.toString().lowercase(),
            "markers" to gson.toJson(markers),
            "statsigOptions" to gson.toJson(statsigOptions.getLoggingCopy()),
        )
        log(event)
    }

    fun addAPICallDiagnostics() {
        val markers = diagnostics?.markers?.get(ContextType.API_CALL)
        if (markers.isNullOrEmpty() ||
            diagnostics?.shouldLogDiagnostics(ContextType.API_CALL) != true
        ) {
            return
        }
        val event = StatsigEvent(DIAGNOSTICS_EVENT)
        event.eventMetadata = mapOf(
            "context" to "api_call",
            "markers" to gson.toJson(markers),
            "setupOptions" to gson.toJson(statsigOptions.getLoggingCopy()),
        )
        events.add(event)
    }

    suspend fun flush() {
        withContext(Dispatchers.IO) {
            addAPICallDiagnostics()
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
