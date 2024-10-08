package com.statsig.sdk.network

import com.statsig.sdk.*
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpc
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

internal class GRPCWorker(
    private val sdkKey: String,
    private val options: StatsigOptions,
    private val statsigMetadata: StatsigMetadata,
    private val errorBoundary: ErrorBoundary,
    private val proxyApi: String,
) : INetworkWorker {
    override val type = NetworkProtocol.GRPC
    override val isPullWorker: Boolean = true
    override val configSpecsFlow: Flow<String>
        get() = throw NotImplementedError("downloadConfigSpecsFlow not implemented")
    override val idListsFlow: Flow<String>
        get() = throw NotImplementedError("idListsFlow not implemented")

    override fun initializeFlows() {}

    private var diagnostics: Diagnostics? = null
    private val channel: ManagedChannel = ManagedChannelBuilder.forTarget(proxyApi).usePlaintext().build()
    private val stub = StatsigForwardProxyGrpc.newBlockingStub(channel)
    private val logger = options.customLogger

    override suspend fun downloadConfigSpecs(sinceTime: Long): Pair<String?, FailureDetails?> {
        val request = ConfigSpecRequest.newBuilder().setSdkKey(this.sdkKey).setSinceTime(sinceTime).build()
        try {
            diagnostics?.startNetworkRequestDiagnostics(KeyType.DOWNLOAD_CONFIG_SPECS, NetworkProtocol.GRPC)
            val response = stub.withDeadlineAfter(options.initTimeoutMs, TimeUnit.MILLISECONDS).getConfigSpec(request)
            diagnostics?.endNetworkRequestDiagnostics(
                KeyType.DOWNLOAD_CONFIG_SPECS,
                NetworkProtocol.GRPC,
                true,
                null,
                null,
            )
            return Pair(response.spec, null)
        } catch (e: StatusException) {
            diagnostics?.endNetworkRequestDiagnostics(
                KeyType.DOWNLOAD_CONFIG_SPECS,
                NetworkProtocol.GRPC,
                false,
                e.message,
                null,
            )
            logger.warn("An status exception was received from forward proxy: $e")
            return Pair(null, FailureDetails(FailureReason.CONFIG_SPECS_NETWORK_ERROR, exception = e))
        }
    }

    override suspend fun getIDLists(): String? {
        throw NotImplementedError("getIDList not implemented")
    }

    override suspend fun logEvents(events: List<StatsigEvent>) {
        throw NotImplementedError("logEvents not implemented")
    }

    override fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
    }

    override fun shutdown() {
        channel.shutdown()
        channel.awaitTermination(5, TimeUnit.SECONDS)
    }
}
