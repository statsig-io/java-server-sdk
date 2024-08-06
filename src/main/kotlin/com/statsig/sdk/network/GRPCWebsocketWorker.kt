package com.statsig.sdk.network

import com.statsig.sdk.*
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpcKt.StatsigForwardProxyCoroutineStub
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse
import grpc.generated.statsig_forward_proxy.configSpecRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

private const val RETRY_LIMIT = 10
private const val INITIAL_RETRY_BACKOFF_MS: Long = 10 * 1000
private const val RETRY_BACKOFF_MULTIPLIER: Long = 5

private val badRetryCodes: Set<Status.Code> = setOf(
    Status.Code.PERMISSION_DENIED,
    Status.Code.UNAUTHENTICATED,
)

internal class GRPCWebsocketWorker(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigMetadata: StatsigMetadata,
    private val statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val proxyApi: String,
) : INetworkWorker {
    override val type = NetworkProtocol.GRPC_WEBSOCKET
    override val isPullWorker: Boolean = false

    private var diagnostics: Diagnostics? = null
    private val channel: ManagedChannel = ManagedChannelBuilder.forTarget(proxyApi).usePlaintext().build()
    private val stub = StatsigForwardProxyCoroutineStub(channel)

    private var remainingRetries = RETRY_LIMIT
    private var backoff: Long = INITIAL_RETRY_BACKOFF_MS
    private var lastUpdateTime: Long = 0
    private var shouldRetry: Boolean = true
    private var connected: Boolean = true
    private var downloadConfigsJob: Job? = null

    private val dcsFlowBacker = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val configSpecsFlow: Flow<String> = dcsFlowBacker.asSharedFlow()
    override val idListsFlow: Flow<String>
        get() = throw NotImplementedError("idListsFlow not implemented")

    override fun initializeFlows() {
        downloadConfigsJob = statsigScope.launch(Dispatchers.IO) { connectWithRetry(this) }
    }

    override suspend fun downloadConfigSpecs(sinceTime: Long): String? {
        diagnostics?.startNetworkRequestDiagnostics(KeyType.DOWNLOAD_CONFIG_SPECS, NetworkProtocol.GRPC_WEBSOCKET)
        val res = withTimeoutOrNull(options.initTimeoutMs) { configSpecsFlow.first() }
        if (res != null) {
            diagnostics?.endNetworkRequestDiagnostics(
                KeyType.DOWNLOAD_CONFIG_SPECS,
                NetworkProtocol.GRPC_WEBSOCKET,
                true,
                null,
                null,
            )
        } else {
            diagnostics?.endNetworkRequestDiagnostics(
                KeyType.DOWNLOAD_CONFIG_SPECS,
                NetworkProtocol.GRPC_WEBSOCKET,
                false,
                "failed to receive config spec within init timeout",
                null,
            )
        }
        return res
    }

    override suspend fun getIDLists(): String? {
        throw NotImplementedError("getIDLists not implemented")
    }

    override suspend fun logEvents(events: List<StatsigEvent>) {
        throw NotImplementedError("logEvents not implemented")
    }

    override fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
    }

    override fun shutdown() {
        downloadConfigsJob?.cancel()
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }

    private suspend fun connectWithRetry(scope: CoroutineScope) {
        println("connect with retry")
        while (remainingRetries > 0 && currentCoroutineContext().isActive) {
            remainingRetries -= 1
            shouldRetry = true
            scope.launch { streamConfigSpec() }.join()
            if (!shouldRetry || remainingRetries == 0) break
            delay(backoff)
            backoff *= RETRY_BACKOFF_MULTIPLIER
            connected = false
        }
        options.customLogger.warning("failed to connect to forward proxy using gRPC streaming")
        errorBoundary.logException(
            "grpcWebSocket: retry exhausted",
            Exception("exhausted retry attempts for gRPC streaming"),
            bypassDedupe = true,
        )
    }

    private suspend fun streamConfigSpec() {
        val request = configSpecRequest {
            this.sdkKey = this@GRPCWebsocketWorker.sdkKey
            this.sinceTime = lastUpdateTime
        }
        val stream = stub.streamConfigSpec(request)
        stream.catch { throwable -> processStreamError(throwable) }
            .collect { response -> processStreamResponse(response) }
    }

    private fun processStreamError(throwable: Throwable) {
        shouldRetry = !(throwable is StatusException && throwable.status.code in badRetryCodes)
        errorBoundary.logException("grpcWebSocket: connection error", throwable, bypassDedupe = true)
    }

    private suspend fun processStreamResponse(response: ConfigSpecResponse) {
        if (!connected) {
            errorBoundary.logException(
                "grpcWebSocket: Reconnected",
                Exception("${response.lastUpdated}"),
                extraInfo = "{\"retryAttempt\": ${RETRY_LIMIT - remainingRetries}}",
                bypassDedupe = true,
            )
        }
        connected = true
        remainingRetries = RETRY_LIMIT
        backoff = INITIAL_RETRY_BACKOFF_MS
        if (response.lastUpdated > lastUpdateTime) {
            lastUpdateTime = response.lastUpdated
            dcsFlowBacker.emit(response.spec)
        }
    }
}
