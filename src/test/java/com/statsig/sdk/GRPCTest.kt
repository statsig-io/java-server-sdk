package com.statsig.sdk

import com.statsig.sdk.network.GRPCWorker
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpc
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse
import io.grpc.stub.StreamObserver
import io.grpc.testing.GrpcServerRule
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GRPCTest {
    @Rule
    @JvmField
    val grpcServerRule: GrpcServerRule = GrpcServerRule().directExecutor()

    @JvmField
    @Rule
    val retry = RetryRule(3)

    private fun setupGRPC(spec: ConfigSpecResponse) {
        val service = object : StatsigForwardProxyGrpc.StatsigForwardProxyImplBase() {
            override fun getConfigSpec(request: ConfigSpecRequest?, responseObserver: StreamObserver<ConfigSpecResponse>?) {
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

        assertEquals("spec-1", worker.downloadConfigSpecs(0))
    }
}
