package com.statsig.sdk

import com.statsig.sdk.network.GRPCWorker
import grpc.generated.statsig_forward_proxy.ConfigSpecResponseKt
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpcKt.StatsigForwardProxyCoroutineImplBase
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyGrpcKt.StatsigForwardProxyCoroutineStub
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecRequest
import grpc.generated.statsig_forward_proxy.StatsigForwardProxyOuterClass.ConfigSpecResponse
import grpc.generated.statsig_forward_proxy.configSpecResponse
import io.grpc.testing.GrpcServerRule
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
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
    private fun ConfigSpecRequest.configSpecResponseWithCheck(genResponse: ConfigSpecResponseKt.Dsl.() -> Unit): ConfigSpecResponse {
        val response = configSpecResponse(genResponse)
        if (response.lastUpdated.toULong() > sinceTime.toULong()) {
            return response
        } else {
            return configSpecResponse { lastUpdated = sinceTime; spec = "no update" }
        }
    }

    private fun setupGRPC(outerGetConfigSpec: suspend ConfigSpecRequest.() -> ConfigSpecResponse) {
        val service = object : StatsigForwardProxyCoroutineImplBase() {
            override suspend fun getConfigSpec(request: ConfigSpecRequest): ConfigSpecResponse {
                return outerGetConfigSpec(request)
            }
        }
        grpcServerRule.serviceRegistry.addService(service)
    }

    private fun setupGRPCStreaming(outerStreamConfigSpec: ConfigSpecRequest.() -> Flow<ConfigSpecResponse>) {
        val service = object : StatsigForwardProxyCoroutineImplBase() {
            override fun streamConfigSpec(request: ConfigSpecRequest): Flow<ConfigSpecResponse> {
                return outerStreamConfigSpec(request)
            }
        }
        grpcServerRule.serviceRegistry.addService(service)
    }

    @Test
    fun testGRPC() = runBlocking {
        setupGRPC { configSpecResponseWithCheck { lastUpdated = 1; spec = "spec-1" } }
        val metadata = StatsigMetadata()
        val options = StatsigOptions()
        val boundary = mockk<ErrorBoundary>()
        val worker = GRPCWorker("sdk", options, metadata, boundary, "localhost")
        val stub = StatsigForwardProxyCoroutineStub(grpcServerRule.channel)

        val stubField = GRPCWorker::class.java.getDeclaredField("stub")
        stubField.isAccessible = true
        stubField.set(worker, stub)

        assertEquals("spec-1", worker.downloadConfigSpecs(0))
    }
}
