package com.statsig.sdk.network

import com.statsig.sdk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.Response

private const val BACKOFF_MULTIPLIER: Int = 10
private const val MS_IN_S: Long = 1000

internal class StatsigTransport(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigMetadata: StatsigMetadata,
    private val statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val sdkConfig: SDKConfigs,
    private val backoffMultiplier: Int = BACKOFF_MULTIPLIER,
) {
    private val externalHttpClient: OkHttpClient = OkHttpClient.Builder().build()
    private var diagnostics: Diagnostics? = null

    var httpWorker: HTTPWorker
    var defaultWorker: INetworkWorker
    var downloadConfigSpecWorker: INetworkWorker
    var getIDListsWorker: INetworkWorker
    var logEventsWorker: INetworkWorker

    private val httpHelper = HTTPHelper(options, errorBoundary)
    init {
        httpWorker = HTTPWorker(sdkKey, options, statsigMetadata, errorBoundary, sdkConfig, backoffMultiplier, httpHelper)
        defaultWorker = generateWorker(options.endpointProxyConfigs[NetworkEndpoint.ALL_ENDPOINTS]) ?: httpWorker
        downloadConfigSpecWorker = generateWorker(options.endpointProxyConfigs[NetworkEndpoint.DOWNLOAD_CONFIG_SPECS]) ?: defaultWorker
        getIDListsWorker = generateWorker(options.endpointProxyConfigs[NetworkEndpoint.GET_ID_LISTS]) ?: defaultWorker
        logEventsWorker = generateWorker(options.endpointProxyConfigs[NetworkEndpoint.LOG_EVENT]) ?: defaultWorker
    }

    fun initialize() {
        downloadConfigSpecWorker.initializeFlows()
        getIDListsWorker.initializeFlows()
    }

    fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
        httpWorker.setDiagnostics(diagnostics)
        downloadConfigSpecWorker.setDiagnostics(diagnostics)
        getIDListsWorker.setDiagnostics(diagnostics)
        logEventsWorker.setDiagnostics(diagnostics)
        httpHelper.setDiagnostics(diagnostics)
    }

    fun configSpecsFlow(): Flow<String> {
        return downloadConfigSpecWorker.configSpecsFlow
    }

    fun idListsFlow(): Flow<String> {
        return downloadConfigSpecWorker.idListsFlow
    }

    fun setStreamingFallback(endpoint: NetworkEndpoint, getDcsFn: suspend () -> String?) {
        when (endpoint) {
            NetworkEndpoint.DOWNLOAD_CONFIG_SPECS -> {
                if (downloadConfigSpecWorker is GRPCWebsocketWorker) {
                    (downloadConfigSpecWorker as GRPCWebsocketWorker).streamingFallback = StreamingFallback(statsigScope, getDcsFn, options.rulesetsSyncIntervalMs)
                }
            }
            else -> {
                // do nothing
            }
        }
    }

    suspend fun downloadConfigSpecs(sinceTime: Long): Pair<String?, FailureDetails?> {
        return downloadConfigSpecWorker.downloadConfigSpecs(sinceTime)
    }

    suspend fun downloadConfigSpecsFromStatsig(sinceTime: Long): Pair<String?, FailureDetails?> {
        return httpWorker.downloadConfigSpecsFromStatsigAPI(sinceTime)
    }

    suspend fun getIDLists(): String? {
        var res = getIDListsWorker.getIDLists()
        if (res == null && options.fallbackToStatsigAPI) {
            if (getIDListsWorker !is HTTPWorker || (getIDListsWorker as HTTPWorker).apiForGetIDLists != STATSIG_API_URL_BASE) {
                res = httpWorker.downloadIDListsFromStatsigAPI()
            }
        }
        return res
    }

    suspend fun postLogs(events: List<StatsigEvent>) {
        try {
            logEventsWorker.logEvents(events)
        } catch (e: Exception) {
            errorBoundary.logException("postLogs", e)
        }
    }

    suspend fun getExternal(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Response? {
        return httpHelper.request(externalHttpClient, url, null, headers).first
    }

    fun shutdown() {
        httpWorker.shutdown()
        defaultWorker.shutdown()
        downloadConfigSpecWorker.shutdown()
        getIDListsWorker.shutdown()
        logEventsWorker.shutdown()
    }

    private fun generateWorker(config: ForwardProxyConfig?): INetworkWorker? {
        val worker = when (config?.proxyProtocol) {
            NetworkProtocol.HTTP -> httpWorker
            NetworkProtocol.GRPC -> GRPCWorker(sdkKey, options, statsigMetadata, errorBoundary, config.proxyAddress)
            NetworkProtocol.GRPC_WEBSOCKET -> GRPCWebsocketWorker(sdkKey, options, statsigScope, errorBoundary, config)
            else -> null
        }
        return worker
    }
}
