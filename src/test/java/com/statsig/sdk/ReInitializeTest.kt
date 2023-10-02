package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test

class ReInitializeTest {
    companion object {
        private lateinit var server: MockWebServer
        private lateinit var options: StatsigOptions
        private val dcs = arrayListOf<RecordedRequest>()
        private val logEvent = arrayListOf<RecordedRequest>()

        @BeforeClass
        @JvmStatic
        internal fun beforeAll() = runBlocking {
            server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        when (request.path) {
                            "/v1/download_config_specs" -> {
                                val downloadConfigSpecsResponse =
                                    StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
                                dcs.add(request)
                                return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                            }
                            "/v1/log_event" -> {
                                logEvent.add(request)
                            }
                        }
                        return MockResponse().setResponseCode(202)
                    }
                }
            options = StatsigOptions(api = server.url("/v1").toString())
        }
    }

    @Test
    fun testReinitializeAfterShutdown() = runBlocking {
        Statsig.initialize("secret-1", StatsigOptions())
        Statsig.shutdown()
        Statsig.initialize("secret-2", options)
        Assert.assertEquals(1, dcs.size)
    }

    @Test
    fun testReinitializeBeforeShutdown() = runBlocking {
        Statsig.initialize("secret-1", StatsigOptions())
        Statsig.initialize("secret-2", options)
        Assert.assertEquals(0, dcs.size)
    }
}
