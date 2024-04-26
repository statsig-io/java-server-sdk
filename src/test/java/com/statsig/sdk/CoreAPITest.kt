package com.statsig.sdk

import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoreAPITest {
    lateinit var server: MockWebServer
    lateinit var driver: StatsigServer
    lateinit var options: StatsigOptions
    var sdkExceptionHitCountdown: CountDownLatch = CountDownLatch(1)
    var sdkExceptionCountdown: CountDownLatch = CountDownLatch(1)

    @Before
    fun setup() {
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/layer_exposure_download_config_specs.json")?.readText() ?: ""
        server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    if ("/v1/log_event" in request.path!!) {
                        return MockResponse().setResponseCode(200)
                    }
                    if ("/v1/sdk_exception" in request.path!!) {
                        sdkExceptionCountdown.await(2, TimeUnit.SECONDS)
                        sdkExceptionHitCountdown.countDown()
                        return MockResponse().setResponseCode(200)
                    }

                    return MockResponse().setResponseCode(404)
                }
            }
        }

        options = StatsigOptions().apply {
            api = server.url("/v1").toString()
            disableDiagnostics = true
        }
        driver = spyk(StatsigServer.create())
    }

    @Test
    fun testCoreAPINotBlockByEB() = runBlocking {
        driver.initialize("secret", options)
        val spiedEB = spyk(driver.errorBoundary)
        driver.errorBoundary = spiedEB
        every {
            spiedEB.getUrl()
        } answers {
            server.url("/v1/sdk_exception").toString()
        }
        driver.errorBoundary.logException("a", Exception("ha"))
        every {
            driver.checkGateSync(any(), any())
        } answers {
            throw Exception("Fake")
        }
        val startTime = System.currentTimeMillis()
        driver.checkGate(StatsigUser("user"), "test_gate")
        val duration = System.currentTimeMillis() - startTime
        assert(duration < 500L)
        sdkExceptionCountdown.countDown()
        assert(sdkExceptionHitCountdown.await(1, TimeUnit.SECONDS))
    }
}
