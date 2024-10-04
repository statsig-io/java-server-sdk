package com.statsig.sdk.network

import com.statsig.sdk.AuthenticationMode
import com.statsig.sdk.Diagnostics
import com.statsig.sdk.ErrorBoundary
import com.statsig.sdk.FailureDetails
import com.statsig.sdk.FailureReason
import com.statsig.sdk.ForwardProxyConfig
import com.statsig.sdk.KeyType
import com.statsig.sdk.NetworkProtocol
import com.statsig.sdk.StatsigEvent
import com.statsig.sdk.StatsigOptions
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpc
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse
import io.grpc.Channel
import io.grpc.Grpc
import io.grpc.ManagedChannelBuilder
import io.grpc.TlsChannelCredentials
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val RETRY_LIMIT = 10
private const val INITIAL_RETRY_BACKOFF_MS: Long = 10 * 1000
private const val RETRY_BACKOFF_MULTIPLIER = 5
private const val FAILOVER_THRESHOLD = 4

internal class GRPCWebsocketWorker(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val proxyConfig: ForwardProxyConfig,
) : INetworkWorker {
    override val type = NetworkProtocol.GRPC_WEBSOCKET
    override val isPullWorker: Boolean = false

    private var diagnostics: Diagnostics? = null
    private val logger = options.customLogger

    private val observer = object : StreamObserver<ConfigSpecResponse> {
        override fun onNext(value: ConfigSpecResponse?) {
            value?.let {
                processStreamResponse(it)
            }
        }

        override fun onError(t: Throwable?) {
            t?.let {
                processStreamErrorOrClose(t)
            }
        }

        override fun onCompleted() {
            processStreamErrorOrClose()
        }
    }

    private val backOffBaseMs = proxyConfig.retryBackoffBaseMs ?: INITIAL_RETRY_BACKOFF_MS
    private val backoffMultiplier = proxyConfig.retryBackoffMultiplier ?: RETRY_BACKOFF_MULTIPLIER
    private val failoverThreshold = proxyConfig.pushWorkerFailoverThreshold ?: FAILOVER_THRESHOLD
    private val retryLimit = proxyConfig.maxRetryAttempt ?: RETRY_LIMIT

    private var backoff = backOffBaseMs
    private var remainingRetries = retryLimit
    private var lastUpdateTime: Long = 0
    private var shouldRetry: Boolean = true
    private var connected: Boolean = true
    private var downloadConfigsJob: Job? = null
    var streamingFallback: StreamingFallback? = null
    private val stub: StatsigForwardProxyGrpc.StatsigForwardProxyStub

    private var channel: Channel
    init {
        try {
            channel = setupChannel()
        } catch (e: Exception) {
            options.customLogger.warn("Failed setup channel falling back to insecure channel")
            channel = ManagedChannelBuilder.forTarget(proxyConfig.proxyAddress).usePlaintext().build()
        }

        stub = StatsigForwardProxyGrpc.newStub(channel)
    }

    private fun setupChannel(): Channel {
        when (proxyConfig.authenticationMode) {
            AuthenticationMode.DISABLED ->
                return ManagedChannelBuilder.forTarget(proxyConfig.proxyAddress).usePlaintext().build()
            AuthenticationMode.MTLS -> {
                val tlsBuilder = TlsChannelCredentials.newBuilder()
                proxyConfig.tlsCertChain.let {
                    if (it != null) {
                        tlsBuilder.trustManager(it)
                    } else {
                        options.customLogger.warn("Failed to get cert chain for tls")
                    }
                    proxyConfig.tlsPrivateKey.let { privateKey ->
                        val privateKeyPassword = proxyConfig.tlsPrivateKeyPassword
                        if (privateKey != null && privateKeyPassword != null) {
                            tlsBuilder.keyManager(privateKey, privateKeyPassword)
                            return Grpc.newChannelBuilder(proxyConfig.proxyAddress, tlsBuilder.build()).build()
                        } else {
                            options.customLogger.warn("Failed to get private key and password for tls")
                            return Grpc.newChannelBuilder(proxyConfig.proxyAddress, tlsBuilder.build()).build()
                        }
                    }
                }
            }
            AuthenticationMode.TLS -> {
                val tlsBuilder = TlsChannelCredentials.newBuilder()
                proxyConfig.tlsCertChain.let {
                    if (it != null) {
                        tlsBuilder.trustManager(it)
                        return Grpc.newChannelBuilder(proxyConfig.proxyAddress, tlsBuilder.build()).build()
                    } else {
                        options.customLogger.warn("Failed to get cert chain for tls")
                    }
                }
            }
        }
        options.customLogger.warn("Falling back to insecure channel")
        return ManagedChannelBuilder.forTarget(proxyConfig.proxyAddress).usePlaintext().build()
    }

    private val dcsFlowBacker = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val configSpecsFlow: Flow<String> = dcsFlowBacker.asSharedFlow()
    override val idListsFlow: Flow<String>
        get() = throw NotImplementedError("idListsFlow not implemented")

    override fun initializeFlows() {
        downloadConfigsJob = statsigScope.launch(Dispatchers.IO) {
            try {
                streamConfigSpec()
            } catch (e: Throwable) {
                processStreamErrorOrClose(e)
            }
        }
    }

    override suspend fun downloadConfigSpecs(sinceTime: Long): Pair<String?, FailureDetails?> {
        diagnostics?.startNetworkRequestDiagnostics(KeyType.DOWNLOAD_CONFIG_SPECS, NetworkProtocol.GRPC_WEBSOCKET)
        val res = withTimeoutOrNull(options.initTimeoutMs) { configSpecsFlow.first() }
        diagnostics?.endNetworkRequestDiagnostics(
            KeyType.DOWNLOAD_CONFIG_SPECS,
            NetworkProtocol.GRPC_WEBSOCKET,
            res != null,
            if (res == null) "failed to receive config spec within init timeout" else null,
            null,
        )
        val details = if (res == null) FailureDetails(FailureReason.CONFIG_SPECS_NETWORK_ERROR, exception = java.lang.Exception("Statsig: failed to receive config spec within init timeout")) else null
        return Pair(res, details)
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
    }

    private fun streamConfigSpec() {
        val request = ConfigSpecRequest.newBuilder().setSdkKey(this@GRPCWebsocketWorker.sdkKey).setSinceTime(lastUpdateTime).build()
        stub.streamConfigSpec(request, observer)
    }

    private fun streamConfigSpecWithBackoff() {
        statsigScope.launch(Dispatchers.IO) {
            delay(backoff)
            backoff *= backoffMultiplier
            remainingRetries--
            streamConfigSpec()
        }
    }

    private fun processStreamErrorOrClose(throwable: Throwable? = null) {
        shouldRetry = remainingRetries > 0
        if ((retryLimit - remainingRetries) == failoverThreshold) {
            streamingFallback?.startBackup(dcsFlowBacker)
        }
        if (shouldRetry) {
            logger.warn("grpcWebSocket: connection error: $throwable")
            errorBoundary.logException("grpcWebSocket: connection error", throwable ?: Exception("connection closed"), bypassDedupe = true)
            streamConfigSpecWithBackoff()
            connected = false
        } else {
            logger.warn("grpcWebSocket: connection error: retry exhausted")
            errorBoundary.logException(
                "grpcWebSocket: retry exhausted",
                Exception("Remaining retry is $remainingRetries, exception is ${throwable?.message}"),
                bypassDedupe = true,
            )
        }
    }

    private fun processStreamResponse(response: ConfigSpecResponse) {
        if (!connected) {
            errorBoundary.logException(
                "grpcWebSocket: Reconnected",
                Exception("${response.lastUpdated}"),
                extraInfo = "{\"retryAttempt\": ${retryLimit - remainingRetries}}",
                bypassDedupe = true,
            )
            streamingFallback?.cancelBackup()
        }
        connected = true
        remainingRetries = retryLimit
        backoff = backOffBaseMs
        if (response.lastUpdated >= lastUpdateTime) {
            lastUpdateTime = response.lastUpdated
            if (!dcsFlowBacker.tryEmit(response.spec)) {
                logger.warn("grpcWebSocket: Failed to emit response")
                errorBoundary.logException(
                    "grpcWebSocket: Failed to emit response",
                    Exception("${response.lastUpdated}"),
                    extraInfo = "{\"retryAttempt\": ${retryLimit - remainingRetries}}",
                    bypassDedupe = true,
                )
            }
        }
    }
}
