package com.statsig.sdk

import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConcurrentInitializeTest {
    private lateinit var options: StatsigOptions
    private var neworkCallCnt = 0

    @Before
    fun setUp() {
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/layer_exposure_download_config_specs.json")?.readText() ?: ""

        val dispatcher: Dispatcher = object : Dispatcher() {
            @Throws(InterruptedException::class)
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/v1/download_config_specs")) {
                    neworkCallCnt++
                    return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                }
                return MockResponse().setResponseCode(404)
            }
        }

        val server = MockWebServer()
        server.dispatcher = dispatcher

        options = StatsigOptions()
        options.api = server.url("/v1").toString()
    }

    @Test
    fun testSingletonConcurrentInitializeAsync() {
        val serverSecret = "secret-test"

        val initializeCalls = 10

        val scope = CoroutineScope(Dispatchers.Default)
        val latch = CountDownLatch(initializeCalls)

        repeat(initializeCalls) {
            scope.launch {
                Statsig.initializeAsync(serverSecret, options)
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        assertEquals(1, neworkCallCnt)
        Statsig.shutdown()
    }

    @Test
    fun testSingletonConcurrentInitialization() {
        val serverSecret = "secret-test"

        val initializeCalls = 10

        val scope = CoroutineScope(Dispatchers.Default)
        val latch = CountDownLatch(initializeCalls)

        repeat(initializeCalls) {
            scope.launch {
                Statsig.initialize(serverSecret, options)
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        assertEquals(1, neworkCallCnt)
        Statsig.shutdown()
    }

    @Test
    fun testMultiInstancesConcurrentInitialization() {
        val serverSecret = "secret-test"

        val initializeCalls = 10
        val server = StatsigServer.create()

        val scope = CoroutineScope(Dispatchers.Default)
        val latch = CountDownLatch(initializeCalls)

        repeat(initializeCalls) {
            scope.launch {
                server.initialize(serverSecret, options)
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        assertEquals(1, neworkCallCnt)
        server.shutdown()
    }

    @Test
    fun testMultiInstancesConcurrentInitializeAsync() {
        val serverSecret = "secret-test"

        val initializeCalls = 10
        val server = StatsigServer.create()

        val scope = CoroutineScope(Dispatchers.Default)
        val latch = CountDownLatch(initializeCalls)

        repeat(initializeCalls) {
            scope.launch {
                server.initializeAsync(serverSecret, options)
            }
        }

        latch.await(10, TimeUnit.SECONDS)
        assertEquals(1, neworkCallCnt)
        server.shutdown()
    }
}
