package com.statsig.sdk

import com.statsig.sdk.network.StatsigTransport
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
const val SAMPLING_SET_RESET_INTERVAL_IN_MS = 60 * 1000L

const val CONFIG_EXPOSURE_EVENT = "statsig::config_exposure"
const val LAYER_EXPOSURE_EVENT = "statsig::layer_exposure"
const val GATE_EXPOSURE_EVENT = "statsig::gate_exposure"
const val DIAGNOSTICS_EVENT = "statsig::diagnostics"

data class SamplingDecision(
    val shouldSendExposure: Boolean, // Whether the exposure event should still be send
    val samplingRate: Long?, // The sampling rate applied (if any)
    val samplingStatus: String?, // The sampling logged status: "logged", "dropped", or null
    val samplingMode: String?, // The sampling mode applied (if any)
)

enum class EntityType {
    GATE,
    CONFIG,
    LAYER
}

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
    private val transport: StatsigTransport,
    private val statsigMetadata: StatsigMetadata,
    private val statsigOptions: StatsigOptions,
    private val sdkConfigs: SDKConfigs,
) {
    private val SAMPLING_SPECIAL_CASE_RULES = listOf("disabled", "default", "")

    private val samplingKeySet = HashSetWithTTL(SAMPLING_SET_RESET_INTERVAL_IN_MS)
    private val executor = Executors.newSingleThreadExecutor()
    private var events = ConcurrentQueue<StatsigEvent>()
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
    private val logger = statsigOptions.customLogger

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
        result: ConfigEvaluation,
        isManualExposure: Boolean = false,
    ) {
        val sampling = getSamplingDecisionAndDetails(user, gateName, result, null, EntityType.GATE)

        if (!sampling.shouldSendExposure) {
            return
        }

        if (!isUniqueExposure(
                user,
                gateName,
                result.ruleID,
                result.booleanValue.toString(),
                "",
            )
        ) {
            return
        }
        val metadata = mutableMapOf(
            "gate" to gateName,
            "gateValue" to result.booleanValue.toString(),
            "ruleID" to result.ruleID,
            "isManualExposure" to isManualExposure.toString(),
        )

        safeAddEvaluationToEvent(result.evaluationDetails, metadata)
        if (result.configVersion != null) {
            metadata["configVersion"] = result.configVersion.toString()
        }

        val statsigMetadataWithSampling = statsigMetadata.copy()

        val event = StatsigEvent(
            GATE_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadataWithSampling,
            result.secondaryExposures,
        )
        addSamplingDetailsToEvent(sampling, event)
        log(event)
    }

    fun logConfigExposure(
        user: StatsigUser?,
        configName: String,
        result: ConfigEvaluation,
        isManualExposure: Boolean,
    ) {
        val sampling = getSamplingDecisionAndDetails(user, configName, result, null, EntityType.GATE)

        if (!sampling.shouldSendExposure) {
            return
        }

        if (!isUniqueExposure(
                user,
                configName,
                result.ruleID,
                "",
                "",
            )
        ) {
            return
        }
        val metadata =
            mutableMapOf("config" to configName, "ruleID" to result.ruleID, "isManualExposure" to isManualExposure.toString(), "rulePassed" to result.booleanValue.toString())
        safeAddEvaluationToEvent(result.evaluationDetails, metadata)
        if (result.configVersion != null) {
            metadata["configVersion"] = result.configVersion.toString()
        }

        val statsigMetadataWithSampling = statsigMetadata.copy()

        val event = StatsigEvent(
            CONFIG_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadataWithSampling,
            result.secondaryExposures,
        )
        addSamplingDetailsToEvent(sampling, event)
        log(event)
    }

    fun logLayerExposure(
        user: StatsigUser?,
        layerExposureMetadata: LayerExposureMetadata,
        layerName: String,
        result: ConfigEvaluation,
        isManualExposure: Boolean = false,
    ) {
        val sampling = getSamplingDecisionAndDetails(user, layerName, result, layerExposureMetadata.parameterName, EntityType.LAYER)

        if (!sampling.shouldSendExposure) {
            return
        }

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

        val statsigMetadataWithSampling = statsigMetadata.copy()

        val event = StatsigEvent(
            LAYER_EXPOSURE_EVENT,
            eventValue = null,
            metadata,
            user,
            statsigMetadataWithSampling,
            layerExposureMetadata.secondaryExposures,
        )
        addSamplingDetailsToEvent(sampling, event)
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
                logger.debug("[StatsigLogger] Event queue is empty.")
                return@withContext
            }

            val flushEvents = events.reset()
            transport.postLogs(flushEvents)
        }
    }

    suspend fun shutdown() {
        flushTimer.cancel()
        clearDeduperTimer.cancel()
        // Order matters!  Shutdown the executor after posting the final batch
        flush()
        samplingKeySet.shutdown()
        executor.shutdown()
    }

    private fun getSamplingDecisionAndDetails(
        user: StatsigUser?,
        configName: String,
        evalResult: ConfigEvaluation,
        layerParamsName: String?,
        entityType: EntityType
    ): SamplingDecision {
        if (shouldSkipSampling(evalResult)) {
            return SamplingDecision(
                shouldSendExposure = true,
                samplingRate = null,
                samplingStatus = null,
                samplingMode = null
            )
        }

        val samplingKey = "${configName}_${evalResult.ruleID}"
        if (!samplingKeySet.contains(samplingKey)) {
            samplingKeySet.add(samplingKey)
            return SamplingDecision(
                shouldSendExposure = true,
                samplingRate = null,
                samplingStatus = null,
                samplingMode = null
            )
        }

        var shouldSendExposures = true
        var samplingRate: Long? = null
        val samplingMode = sdkConfigs.getConfigsStrValue("sampling_mode")
        val specialCaseSamplingRate = sdkConfigs.getConfigNumValue("special_case_sampling_rate")

        val exposureKey: String = when (entityType) {
            EntityType.GATE, EntityType.CONFIG -> computeSamplingKeyForGateOrConfig(configName, evalResult.ruleID, evalResult.booleanValue, user?.userID, user?.customIDs)
            EntityType.LAYER -> computeSamplingKeyForLayer(configName, evalResult.configDelegate ?: "", layerParamsName ?: "", evalResult.ruleID, user?.userID, user?.customIDs)
        }

        if (SAMPLING_SPECIAL_CASE_RULES.contains(evalResult.ruleID) && specialCaseSamplingRate != null) {
            shouldSendExposures = isHashInSamplingRate(exposureKey, specialCaseSamplingRate.toLong())
            samplingRate = specialCaseSamplingRate.toLong()
        } else {
            evalResult.samplingRate?.let {
                shouldSendExposures = isHashInSamplingRate(exposureKey, it)
                samplingRate = it
            }
        }

        val samplingStatus = when {
            samplingRate == null -> null
            shouldSendExposures -> "logged"
            else -> "dropped"
        }

        return when (samplingMode) {
            "on" -> SamplingDecision(
                shouldSendExposure = shouldSendExposures,
                samplingRate = samplingRate,
                samplingStatus = samplingStatus,
                samplingMode = "on"
            )
            "shadow" -> SamplingDecision(
                shouldSendExposure = true, // Always send events in shadow mode
                samplingRate = samplingRate,
                samplingStatus = samplingStatus,
                samplingMode = "shadow"
            )
            else -> SamplingDecision(
                shouldSendExposure = true,
                samplingRate = null,
                samplingStatus = null,
                samplingMode = null
            )
        }
    }

    private fun getEventQueueCap(): Int {
        try {
            if (eventQueueSize is Int) {
                return eventQueueSize as Int
            }
            val size = sdkConfigs.getConfigNumValue("event_queue_size")?.toInt()
            eventQueueSize = if (size is Int && size > 0) size else DEFAULT_MAX_EVENTS
            return eventQueueSize as Int
        } catch (_: Exception) {
            return DEFAULT_MAX_EVENTS
        }
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

    //  ----------------
    //  Sampling helper
    //  ----------------
    private fun addSamplingDetailsToEvent(samplingDecision: SamplingDecision, event: StatsigEvent) {
        if (samplingDecision.samplingRate != null) {
            event.statsigMetadata?.samplingRate = samplingDecision.samplingRate
        }
        if (samplingDecision.samplingStatus != null) {
            event.statsigMetadata?.samplingStatus = samplingDecision.samplingStatus
        }
        if (samplingDecision.samplingMode != null) {
            event.statsigMetadata?.samplingMode = samplingDecision.samplingMode
        }
    }

    private fun computeSamplingKeyForGateOrConfig(
        gateName: String,
        ruleId: String,
        value: Boolean,
        userId: String?,
        customIds: Map<String, String>?
    ): String {
        val userKey = computeUserKey(userId, customIds)
        return "n:$gateName;u:$userKey;r:$ruleId;v:$value"
    }

    private fun computeSamplingKeyForLayer(
        layerName: String,
        experimentName: String,
        parameterName: String,
        ruleId: String,
        userId: String?,
        customIds: Map<String, String>?
    ): String {
        val userKey = computeUserKey(userId, customIds)
        return "n:$layerName;e:$experimentName;p:$parameterName;u:$userKey;r:$ruleId"
    }

    private fun computeUserKey(userId: String?, customIds: Map<String, String>?): String {
        var userKey = "u:$userId;"

        if (customIds != null) {
            for ((key, value) in customIds) {
                userKey += "$key:$value;"
            }
        }

        return userKey
    }

    private fun shouldSkipSampling(
        evalResult: ConfigEvaluation,
    ): Boolean {
        val samplingMode = sdkConfigs.getConfigsStrValue("sampling_mode")
        if (samplingMode.isNullOrEmpty() || samplingMode.equals("none")) {
            return true
        }

        val tier = statsigOptions.getEnvironment()?.get("tier") ?: "production" // if its null, then default to prod
        if (!tier.equals("production", ignoreCase = true)) {
            return true
        }

        if (evalResult.forwardAllExposures) {
            return true
        }

        if (evalResult.ruleID.endsWith(":override") || evalResult.ruleID.endsWith(":id_override")) {
            return true
        }

        return false
    }

    private fun isHashInSamplingRate(key: String, samplingRate: Long): Boolean {
        val hashValue = Hashing.sha256ToLong(key)
        return hashValue % samplingRate == 0L
    }
}
