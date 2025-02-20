package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test

class ExceptionHandlerTest {
    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var evaluator: Evaluator
    private lateinit var driver: StatsigServer
    private lateinit var user: StatsigUser
    private lateinit var options: StatsigOptions

    @Before
    fun setUp() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
        user = StatsigUser("abc")
        eventLogInputCompletable = CompletableDeferred()

        val mockGateResponse = APIFeatureGate("a_gate", true, "ruleID", arrayListOf(), EvaluationReason.DEFAULT, null, null)
        val mockResponseBody = gson.toJson(mockGateResponse)

        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/layer_exposure_download_config_specs.json")?.readText() ?: ""

        val server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    if ("/v1/check_gate" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(mockResponseBody)
                    }
                    if ("/v1/log_event" in request.path!!) {
                        return TestUtil.mockLogEventEndpoint(request, eventLogInputCompletable)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        }

        options = StatsigOptions().apply {
            api = server.url("/v1").toString()
            disableDiagnostics = true
        }

        driver = StatsigServer.create()
    }

    @Test
    fun testDaemonThreadException() = runBlocking {
        driver.initialize("server-key", options)
        val daemon = Thread({
            throw Exception("Throwing from daemon thread")
        })
        daemon.isDaemon = true
        daemon.start()
        assert(driver.initialized.get())
    }

    @Test
    fun testThrowingNonDaemon() = runBlocking {
        driver.initialize("server-key", options)
        val nonDaemon = Thread({
            throw Exception("Throwing from non-daemon thread")
        })
        nonDaemon.start()
        // For exception throw in other thread, we don't handle
        assert(driver.initialized.get())
        driver.shutdown()
        assert(!driver.initialized.get())
    }

    @Test
    fun testThrowingOnSameThread() = runBlocking {
        val t = Thread {
            runBlocking {
                driver.initialize("server-key", options)
            }
            throw java.lang.Exception("throw exception")
        }
        t.start()
        assert(!driver.initialized.get())
    }
}
