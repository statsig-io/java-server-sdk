package com.statsig.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.Executors

const val DEFAULT_MAX_EVENTS: Int = 1000
const val MAX_DEDUPER_SIZE: Int = 10000
const val FLUSH_TIMER_MS: Long = 60000
const val CLEAR_DEDUPER_MS: Long = 60 * 1000

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
    private val sdkConfigs: SDKConfigs,
) {

    private val executor = Executors.newSingleThreadExecutor()
    private var events = LockableArray<StatsigEvent>()
    private val flushTimer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(FLUSH_TIMER_MS)
            flush()
        }
    }
    private var deduper = Collections.synchronizedSet<String>(mutableSetOf())
    private val clearDeduperTimer = coroutineScope.launch {
        while (coroutineScope.isActive) {
            delay(CLEAR_DEDUPER_MS)
            deduper.clear()
        }
    }
    private val gson = Utils.getGson()
    internal var diagnostics: Diagnostics? = null
    private var eventQueueSize: Int? = null
    fun log(event: StatsigEvent) {
        if (statsigOptions.disableAllLogging) {
            return
        }
        events.add(event)
        if (events.size() >= getEventQueueCap()) {
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
        if (!isUniqueExposure(
                user,
                gateName,
                ruleID,
                value.toString(),
                "",
            )
        ) {
            return
        }
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
        if (!isUniqueExposure(
                user,
                configName,
                ruleID,
                "",
                "",
            )
        ) {
            return
        }
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
        if (!isUniqueExposure(
                user,
                layerExposureMetadata.config,
                layerExposureMetadata.ruleID,
                layerExposureMetadata.parameterName,
                layerExposureMetadata.allocatedExperiment,
            )
        ) {
            return
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

    private fun addDiagnostics(context: ContextType) {
        val markers = diagnostics?.markers?.get(context)
        diagnostics?.clearContext(context)
        if (markers.isNullOrEmpty() ||
            diagnostics?.shouldLogDiagnostics(context) != true
        ) {
            return
        }
        val event = StatsigEvent(DIAGNOSTICS_EVENT)
        event.eventMetadata = mapOf(
            "context" to context.name.lowercase(),
            "markers" to gson.toJson(markers),
        )
        if (statsigOptions.disableAllLogging) {
            return
        }
        events.add(event)
    }

    suspend fun flush() {
        withContext(Dispatchers.IO) {
            addDiagnostics(ContextType.API_CALL)
            addDiagnostics(ContextType.GET_CLIENT_INITIALIZE_RESPONSE)
            if (events.size() == 0) {
                return@withContext
            }

            val flushEvents = events.reset()
            network.postLogs(flushEvents, statsigMetadata)
        }
    }

    suspend fun shutdown() {
        flushTimer.cancel()
        clearDeduperTimer.cancel()
        // Order matters!  Shutdown the executor after posting the final batch
        flush()
        executor.shutdown()
    }

    private fun getEventQueueCap(): Int {
        if (eventQueueSize is Int) {
            return eventQueueSize as Int
        }
        eventQueueSize = sdkConfigs.getConfigNumValue("event_queue_size")?.toInt()
        return eventQueueSize ?: DEFAULT_MAX_EVENTS
    }

    private fun isUniqueExposure(user: StatsigUser?, configName: String, ruleID: String, value: String, allocatedExperiment: String): Boolean {
        if (user == null) return true
        if (deduper.size >= MAX_DEDUPER_SIZE) {
            deduper.clear()
            return true
        }
        val customIDKeys = "${user.customIDs?.keys?.joinToString()}:${user.customIDs?.values?.joinToString()}"
        val dedupeKey = "${user.userID}:$customIDKeys:$configName:$ruleID:$value:$allocatedExperiment"
        return deduper.add(dedupeKey)
    }
}
