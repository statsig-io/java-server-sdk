package com.statsig.sdk

import com.statsig.sdk.network.GRPCWebsocketWorker
import com.statsig.sdk.network.GRPCWorker
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpc
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse
import io.grpc.stub.StreamObserver
import io.grpc.testing.GrpcServerRule
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

class GRPCTest {
    @Rule
    @JvmField
    val grpcServerRule: GrpcServerRule = GrpcServerRule().directExecutor()

    @JvmField
    @Rule
    val retry = RetryRule(3)

    private fun setupGRPC(spec: ConfigSpecResponse, shouldThrowOnStreaming: Boolean = false, blockStreamTime: Long? = null) {
        val service = object : StatsigForwardProxyGrpc.StatsigForwardProxyImplBase() {
            override fun getConfigSpec(request: ConfigSpecRequest?, responseObserver: StreamObserver<ConfigSpecResponse>?) {
                responseObserver?.onNext(spec)
                responseObserver?.onCompleted()
            }

            override fun streamConfigSpec(
                request: ConfigSpecRequest?,
                responseObserver: StreamObserver<ConfigSpecResponse>?,
            ) {
                if (shouldThrowOnStreaming) {
                    responseObserver?.onError(Exception("io exception"))
                }
                if (blockStreamTime != null) {
                    runBlocking {
                        delay(blockStreamTime)
                    }
                }
                responseObserver?.onNext(spec)
                responseObserver?.onCompleted()
            }
        }
        grpcServerRule.serviceRegistry.addService(service)
    }

    @Test
    fun testGRPC() = runBlocking {
        setupGRPC(ConfigSpecResponse.newBuilder().setSpec("spec-1").setLastUpdated(123).build())
        val metadata = StatsigMetadata()
        val options = StatsigOptions()
        val boundary = mockk<ErrorBoundary>()
        val worker = GRPCWorker("sdk", options, metadata, boundary, "localhost")
        val stub = StatsigForwardProxyGrpc.newBlockingStub(grpcServerRule.channel)

        val stubField = GRPCWorker::class.java.getDeclaredField("stub")
        stubField.isAccessible = true
        stubField.set(worker, stub)

        assertEquals(Pair("spec-1", null), worker.downloadConfigSpecs(0))
    }

    @Test
    fun testTLSConfigurationError() = runBlocking {
        setupGRPC(ConfigSpecResponse.newBuilder().setSpec("spec-1").setLastUpdated(123).build())
        val options = StatsigOptions()
        val boundary = mockk<ErrorBoundary>()
        val scope = CoroutineScope(Dispatchers.Default)
        val proxyConfig = ForwardProxyConfig(
            "proxy:8000",
            NetworkProtocol.GRPC_WEBSOCKET,
            retryBackoffMultiplier = 1,
            retryBackoffBaseMs = 10,
            authenticationMode = AuthenticationMode.TLS,
            // We will try catch this invalid cert chain and fallback to insecure channel
            tlsCertChain = ByteArrayInputStream("invalid".toByteArray()),
        )
        val worker = GRPCWebsocketWorker("sdk", options, scope, boundary, proxyConfig)
        val stub = StatsigForwardProxyGrpc.newStub(grpcServerRule.channel)

        val stubField = GRPCWebsocketWorker::class.java.getDeclaredField("stub")
        stubField.isAccessible = true
        stubField.set(worker, stub)
        worker.initializeFlows()
        // In this test, stream will be successful, because we mock
        assertEquals(Pair("spec-1", null), worker.downloadConfigSpecs(0))
    }

    /*
* Test when server returns error we start retry
* This error can be because of error internal unavailability, authentication failed
* */
    @Test
    fun testTimeout() = runBlocking {
        setupGRPC(ConfigSpecResponse.newBuilder().setSpec("spec-1").setLastUpdated(123).build(), blockStreamTime = 500)
        val options = StatsigOptions(initTimeoutMs = 100)
        val boundary = mockk<ErrorBoundary>()
        val scope = CoroutineScope(Dispatchers.Default)
        val proxyConfig = ForwardProxyConfig(
            "proxy:8000",
            NetworkProtocol.GRPC_WEBSOCKET,
            retryBackoffMultiplier = 1,
            retryBackoffBaseMs = 10,
            authenticationMode = AuthenticationMode.DISABLED,
        )
        val worker = GRPCWebsocketWorker("sdk", options, scope, boundary, proxyConfig)
        val stub = StatsigForwardProxyGrpc.newStub(grpcServerRule.channel)

        val stubField = GRPCWebsocketWorker::class.java.getDeclaredField("stub")
        stubField.isAccessible = true
        stubField.set(worker, stub)
        worker.initializeFlows()
        val details = worker.downloadConfigSpecs(0)
        assert(details.first == null)
        assert(details.second?.exception?.message?.contains("failed to receive config spec within init timeout") == true)
    }
}
