package com.statsig.sdk

import okhttp3.Response
import java.util.*

const val NANO_IN_MS = 1_000_000.0
const val MAX_SAMPLING_RATE = 10_000
const val MAX_MARKERS = 50

internal class Diagnostics(private var isDisabled: Boolean, private var logger: StatsigLogger) {
    var diagnosticsContext: ContextType = ContextType.INITIALIZE
    private val samplingRates: MutableMap<String, Int> = mutableMapOf(
        "dcs" to 0,
        "log" to 0,
        "initialize" to MAX_SAMPLING_RATE,
        "idlist" to 0,
        "api_call" to 0,
        "gcir" to 0,
    )
    internal var markers: DiagnosticsMarkers = Collections.synchronizedMap(mutableMapOf())
    fun setSamplingRate(rates: Map<String, Int>) {
        rates.forEach { entry ->
            if (samplingRates.containsKey(entry.key)) {
                val rate = entry.value
                val samplingRate = if (rate in 0..MAX_SAMPLING_RATE) {
                    rate
                } else {
                    if (rate < 0) 0 else MAX_SAMPLING_RATE
                }
                samplingRates[entry.key] = samplingRate
            }
        }
    }

    fun markStart(
        key: KeyType,
        step: StepType? = null,
        context: ContextType? = null,
        additionalMarker: Marker? = null,
    ): String? {
        val contextType = context ?: diagnosticsContext
        if (contextType == ContextType.API_CALL && isDisabled) {
            return null
        }
        val marker = Marker(
            key = key,
            action = ActionType.START,
            networkProtocol = additionalMarker?.networkProtocol,
            timestamp = System.nanoTime() / NANO_IN_MS,
            step = step,
        )
        when (key) {
            KeyType.GET_ID_LIST -> {
                marker.markerID = additionalMarker?.markerID
            }
            KeyType.GET_ID_LIST_SOURCES -> {
                if (step == StepType.PROCESS) {
                    marker.idListCount = additionalMarker?.idListCount
                }
            }
            else -> {
                // No additional action needed for other KeyType values
            }
        }
        when (contextType) {
            ContextType.API_CALL -> {
                marker.configName = additionalMarker?.configName
                marker.markerID = additionalMarker?.markerID
            }
            else -> {
                // No additional action needed for other ContextType values
            }
        }
        if (contextType == ContextType.API_CALL || contextType == ContextType.GET_CLIENT_INITIALIZE_RESPONSE) {
            marker.markerID = key.name + "_" + (markers?.get(contextType)?.count() ?: 0)
        }
        this.addMarker(marker, contextType)
        return marker.markerID
    }

    fun markEnd(
        key: KeyType,
        success: Boolean,
        step: StepType? = null,
        context: ContextType? = null,
        additionalMarker: Marker? = null,
    ) {
        val contextType = context ?: diagnosticsContext
        if (contextType == ContextType.API_CALL && isDisabled) {
            return
        }
        val marker = Marker(
            key = key,
            action = ActionType.END,
            networkProtocol = additionalMarker?.networkProtocol,
            success = success,
            error = additionalMarker?.error,
            timestamp = System.nanoTime() / NANO_IN_MS,
            step = step,
        )
        when (key) {
            KeyType.DOWNLOAD_CONFIG_SPECS -> {
                if (step == StepType.NETWORK_REQUEST) {
                    marker.sdkRegion = additionalMarker?.sdkRegion
                    marker.statusCode = additionalMarker?.statusCode
                }
            }
            KeyType.GET_ID_LIST -> {
                if (step == StepType.NETWORK_REQUEST) {
                    marker.markerID = additionalMarker?.markerID
                    marker.sdkRegion = additionalMarker?.sdkRegion
                    marker.statusCode = additionalMarker?.statusCode
                } else if (step == StepType.PROCESS) {
                    marker.markerID = additionalMarker?.markerID
                }
            }
            KeyType.GET_ID_LIST_SOURCES -> {
                if (step == StepType.NETWORK_REQUEST) {
                    marker.sdkRegion = additionalMarker?.sdkRegion
                    marker.statusCode = additionalMarker?.statusCode
                }
            }
            KeyType.OVERALL -> {
                marker.reason = additionalMarker?.reason
            }
            else -> {
                // No additional action needed for other KeyType values
            }
        }
        when (contextType) {
            ContextType.API_CALL -> {
                marker.configName = additionalMarker?.configName
                marker.markerID = additionalMarker?.markerID
            }
            ContextType.GET_CLIENT_INITIALIZE_RESPONSE -> {
                marker.markerID = additionalMarker?.markerID
            }
            else -> {
                // No additional action needed for other ContextType values
            }
        }
        this.addMarker(marker, contextType)
    }

    internal fun shouldLogDiagnostics(context: ContextType): Boolean {
        val samplingKey: String =
            when (context) {
                ContextType.CONFIG_SYNC -> "dcs"
                ContextType.INITIALIZE -> "initialize"
                ContextType.API_CALL -> "api_call"
                ContextType.GET_CLIENT_INITIALIZE_RESPONSE -> "gcir"
                ContextType.GET_EVALUATIONS_FOR_USER -> "gcir"
            }
        val rand = Math.random() * MAX_SAMPLING_RATE
        return samplingRates[samplingKey] ?: 0 > rand
    }

    private fun addMarker(marker: Marker, context: ContextType) {
        if (this.markers[context] == null) {
            this.markers[context] = Collections.synchronizedList(mutableListOf())
        }
        if ((this.markers[context]?.size ?: 0) >= MAX_MARKERS) {
            return
        }
        this.markers[context]?.add(marker)
    }

    fun logDiagnostics(context: ContextType) {
        val markersToLog = markers[context]
        clearContext(context)
        if (markersToLog == null || markersToLog.size <= 0 || !shouldLogDiagnostics(context)) {
            return
        }
        logger.logDiagnostics(context, markersToLog)
    }

    fun startNetworkRequestDiagnostics(key: KeyType?, networkProtocol: NetworkProtocol) {
        if (key == null) {
            return
        }
        this.markStart(
            key,
            step = StepType.NETWORK_REQUEST,
            additionalMarker = Marker(networkProtocol = networkProtocol),
        )
    }

    fun endNetworkRequestDiagnostics(
        key: KeyType?,
        networkProtocol: NetworkProtocol,
        success: Boolean,
        error: String?,
        response: Response?,
    ) {
        if (key == null) {
            return
        }
        val marker = if (response != null) {
            Marker(
                sdkRegion = response.headers["x-statsig-region"],
                statusCode = response.code,
            )
        } else {
            Marker()
        }
        marker.networkProtocol = networkProtocol
        marker.error = error
        this.markEnd(key, success, StepType.NETWORK_REQUEST, additionalMarker = marker)
    }

    fun getDiagnosticKeyFromURL(url: String): KeyType? {
        if (url.contains("/download_config_specs")) {
            return KeyType.DOWNLOAD_CONFIG_SPECS
        }
        if (url.contains("/get_id_lists")) {
            return KeyType.GET_ID_LIST_SOURCES
        }
        return null
    }

    fun clearContext(context: ContextType) {
        this.markers[context] = Collections.synchronizedList(mutableListOf())
    }
}
