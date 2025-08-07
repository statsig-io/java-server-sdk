package com.statsig.sdk

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class InitializationTimeoutTest {
    private lateinit var statsig: StatsigServer
    private lateinit var options: StatsigOptions
    val server = MockWebServer()

    @Before
    fun setUp() {
        setupWebserver()
    }

    fun setupWebserver() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        val downloadConfigSpecsResponse =
                            StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    } else if ("/v1/get_id_lists" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(Gson().toJson(mapOf("a_list" to null))).throttleBody(bytesPerPeriod = 1, period = 5, TimeUnit.SECONDS) // 1 byte every 5 seconds
                    }

                    return MockResponse().setResponseCode(202)
                }
            }
    }

    @Test
    fun testInitialize() {
        val statsig = StatsigServer.create()
        runBlocking {
            statsig.initialize("secret-", StatsigOptions(api = server.url("/v1").toString(), initTimeoutMs = 1000, logLevel = LogLevel.DEBUG))
        }
        val gate = statsig.getFeatureGate(StatsigUser("test"), "always_on_gate")
        assert(gate.evaluationDetails!!.reason == EvaluationReason.NETWORK)
    }
}
