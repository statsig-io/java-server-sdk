package com.statsig.sdk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class StatsigPollingTest {
    private lateinit var statsig: StatsigServer
    private lateinit var options: StatsigOptions
    private val logLines: MutableList<String> = mutableListOf()

    @Before
    fun setUp() {
        options =
            StatsigOptions(
                fallbackToStatsigAPI = true,
                rulesetsSyncIntervalMs = 1000,
                customLogger =
                object : LoggerInterface {
                    override fun error(message: String) {
                    }

                    override fun warn(message: String) {
                    }

                    override fun info(message: String) {
                        logLines.add(message)
                    }

                    override fun debug(message: String) {
                    }

                    override fun setLogLevel(level: LogLevel) {
                    }
                },
                logLevel = LogLevel.INFO,
            )
        statsig = StatsigServer.create()
    }

    @After
    fun cleanup() {
        logLines.clear()
        statsig.shutdown()
    }

    fun setupWebServer(success: Boolean): MockWebServer {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        if (success) {
                            val downloadConfigSpecsResponse =
                                StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
                            return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                        } else {
                            return MockResponse().setResponseCode(500)
                        }
                    }

                    return MockResponse().setResponseCode(202)
                }
            }
        return server
    }

    @Test
    fun testPollFromSources() {
        val server = setupWebServer(true)
        options.api = server.url("/v1").toString()
        runBlocking {
            statsig.initialize("secret-key", options)
        }
        val dcsRequestCount =
            logLines
                .filter {
                    it.startsWith("[StatsigHTTPHelper]") && it.contains("v1/download_config_specs")
                }.size
        assert(dcsRequestCount == 1)
    }

    @Test
    fun testFallbackOnPollFromSources() {
        val server = setupWebServer(false)
        options.api = server.url("/v1").toString()
        runBlocking {
            statsig.initialize("secret-key", options)
        }
        val dcsRequestCount =
            logLines
                .filter {
                    it.startsWith("[StatsigHTTPHelper]") && it.contains("v1/download_config_specs")
                }.size
        assert(dcsRequestCount == 2)
    }
}
