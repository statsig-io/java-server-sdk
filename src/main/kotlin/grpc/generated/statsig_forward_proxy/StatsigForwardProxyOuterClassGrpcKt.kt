package grpc.generated.statsig_forward_proxy

import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpc.getServiceDescriptor
import io.grpc.CallOptions
import io.grpc.CallOptions.DEFAULT
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServerServiceDefinition.builder
import io.grpc.ServiceDescriptor
import io.grpc.Status.UNIMPLEMENTED
import io.grpc.StatusException
import io.grpc.kotlin.AbstractCoroutineServerImpl
import io.grpc.kotlin.AbstractCoroutineStub
import io.grpc.kotlin.ClientCalls.serverStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlinx.coroutines.flow.Flow
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Holder for Kotlin coroutine-based client and server APIs for
 * statsig_forward_proxy.StatsigForwardProxy.
 */
public object StatsigForwardProxyGrpcKt {
    public const val SERVICE_NAME: String = StatsigForwardProxyGrpc.SERVICE_NAME

    @JvmStatic
    public val serviceDescriptor: ServiceDescriptor
        get() = getServiceDescriptor()

    public val getConfigSpecMethod:
        MethodDescriptor<StatsigForwardProxyOuterClass.ConfigSpecRequest, StatsigForwardProxyOuterClass.ConfigSpecResponse>
        @JvmStatic
        get() = StatsigForwardProxyGrpc.getGetConfigSpecMethod()

    public val streamConfigSpecMethod:
        MethodDescriptor<StatsigForwardProxyOuterClass.ConfigSpecRequest, StatsigForwardProxyOuterClass.ConfigSpecResponse>
        @JvmStatic
        get() = StatsigForwardProxyGrpc.getStreamConfigSpecMethod()

    /**
     * A stub for issuing RPCs to a(n) statsig_forward_proxy.StatsigForwardProxy service as suspending
     * coroutines.
     */
    @StubFor(StatsigForwardProxyGrpc::class)
    public class StatsigForwardProxyCoroutineStub @JvmOverloads constructor(
        channel: Channel,
        callOptions: CallOptions = DEFAULT,
    ) : AbstractCoroutineStub<StatsigForwardProxyCoroutineStub>(channel, callOptions) {
        override fun build(channel: Channel, callOptions: CallOptions): StatsigForwardProxyCoroutineStub = StatsigForwardProxyCoroutineStub(channel, callOptions)

        /**
         * Executes this RPC and returns the response message, suspending until the RPC completes
         * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
         * corresponding
         * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
         * with the corresponding exception as a cause.
         *
         * @param request The request message to send to the server.
         *
         * @param headers Metadata to attach to the request.  Most users will not need this.
         *
         * @return The single response from the server.
         */
        public suspend fun getConfigSpec(
            request: StatsigForwardProxyOuterClass.ConfigSpecRequest,
            headers: Metadata = Metadata(),
        ): StatsigForwardProxyOuterClass.ConfigSpecResponse =
            unaryRpc(
                channel,
                StatsigForwardProxyGrpc.getGetConfigSpecMethod(),
                request,
                callOptions,
                headers,
            )

        /**
         * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
         * server as they arrive.  That flow finishes normally if the server closes its response with
         * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
         * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
         * is cancelled with that exception as a cause.
         *
         * @param request The request message to send to the server.
         *
         * @param headers Metadata to attach to the request.  Most users will not need this.
         *
         * @return A flow that, when collected, emits the responses from the server.
         */
        public fun streamConfigSpec(
            request: StatsigForwardProxyOuterClass.ConfigSpecRequest,
            headers: Metadata = Metadata(),
        ): Flow<StatsigForwardProxyOuterClass.ConfigSpecResponse> =
            serverStreamingRpc(
                channel,
                StatsigForwardProxyGrpc.getStreamConfigSpecMethod(),
                request,
                callOptions,
                headers,
            )
    }

    /**
     * Skeletal implementation of the statsig_forward_proxy.StatsigForwardProxy service based on
     * Kotlin coroutines.
     */
    public abstract class StatsigForwardProxyCoroutineImplBase(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ) : AbstractCoroutineServerImpl(coroutineContext) {
        /**
         * Returns the response to an RPC for statsig_forward_proxy.StatsigForwardProxy.getConfigSpec.
         *
         * If this method fails with a [StatusException], the RPC will fail with the corresponding
         * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
         * the RPC will fail
         * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
         * fail with `Status.UNKNOWN` with the exception as a cause.
         *
         * @param request The request from the client.
         */
        public open suspend fun getConfigSpec(request: StatsigForwardProxyOuterClass.ConfigSpecRequest): StatsigForwardProxyOuterClass.ConfigSpecResponse = throw
            StatusException(UNIMPLEMENTED.withDescription("Method statsig_forward_proxy.StatsigForwardProxy.getConfigSpec is unimplemented"))

        /**
         * Returns a [Flow] of responses to an RPC for
         * statsig_forward_proxy.StatsigForwardProxy.StreamConfigSpec.
         *
         * If creating or collecting the returned flow fails with a [StatusException], the RPC
         * will fail with the corresponding [io.grpc.Status].  If it fails with a
         * [java.util.concurrent.CancellationException], the RPC will fail with status
         * `Status.CANCELLED`.  If creating
         * or collecting the returned flow fails for any other reason, the RPC will fail with
         * `Status.UNKNOWN` with the exception as a cause.
         *
         * @param request The request from the client.
         */
        public open fun streamConfigSpec(request: StatsigForwardProxyOuterClass.ConfigSpecRequest): Flow<StatsigForwardProxyOuterClass.ConfigSpecResponse> = throw
            StatusException(UNIMPLEMENTED.withDescription("Method statsig_forward_proxy.StatsigForwardProxy.StreamConfigSpec is unimplemented"))

        final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
            .addMethod(
                unaryServerMethodDefinition(
                    context = this.context,
                    descriptor = StatsigForwardProxyGrpc.getGetConfigSpecMethod(),
                    implementation = ::getConfigSpec,
                ),
            )
            .addMethod(
                serverStreamingServerMethodDefinition(
                    context = this.context,
                    descriptor = StatsigForwardProxyGrpc.getStreamConfigSpecMethod(),
                    implementation = ::streamConfigSpec,
                ),
            ).build()
    }
}
