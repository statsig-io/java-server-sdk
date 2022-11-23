package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.statsig.sdk.TestUtil.Companion.captureEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ExposureLoggingTest {
    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var evaluator: Evaluator
    private lateinit var driver: StatsigServer
    private lateinit var user: StatsigUser

    @Before
    fun setUp() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
        user = StatsigUser("abc")
        eventLogInputCompletable = CompletableDeferred()

        val mockGateResponse = APIFeatureGate("a_gate", true, "ruleID")
        val mockResponseBody = gson.toJson(mockGateResponse)

        eventLogInputCompletable = CompletableDeferred()
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/layer_exposure_download_config_specs.json")?.readText() ?: ""

        val server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    when (request.path) {
                        "/v1/download_config_specs" -> {
                            return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                        }
                        "/v1/check_gate" -> {
                            return MockResponse().setResponseCode(200).setBody(mockResponseBody)
                        }
                        "/v1/log_event" -> {
                            val logBody = request.body.readUtf8()
                            eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput::class.java))
                            return MockResponse().setResponseCode(200)
                        }
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        }

        val options = StatsigOptions().apply {
            api = server.url("/v1").toString()
        }

        driver = StatsigServer.create("secret-local", options)

        val privateEvaluatorField = driver.javaClass.getDeclaredField("configEvaluator")
        privateEvaluatorField.isAccessible = true
        val evaluator = privateEvaluatorField[driver] as Evaluator
    }

    @Test
    fun testManualLogLayerParameterExposure() = runBlocking {
        driver.initialize()
        driver.manuallyLogLayerParameterExposure(user, "a_layer", "a_param")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
        assertEquals(events[0].eventMetadata?.get("isManualExposure") ?: "", "true")
    }

    @Test
    fun testCheckGateWithExposureLoggingDisabled() = runBlocking {
        driver.initialize()
        driver.checkGateWithExposureLoggingDisabled(user, "a_gate")
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }

    @Test
    fun testManuallyLogGateExposure() = runBlocking {
        driver.initialize()
        driver.manuallyLogGateExposure(user, "a_gate")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
        assertEquals(events[0].eventMetadata?.get("isManualExposure") ?: "", "true")
    }

    @Test
    fun testCheckGateWithExposureLoggingEnabled() = runBlocking {
        driver.initialize()
        driver.checkGate(user, "always_on_gate")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetConfigWithExposureLoggingDisabled() = runBlocking {
        driver.initialize()
        driver.getConfigWithExposureLoggingDisabled(user, "a_config")
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }

    @Test
    fun testGetConfigWithExposureLoggingEnabled() = runBlocking {
        driver.initialize()
        driver.getConfig(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testManualLogConfigExposure() = runBlocking {
        driver.initialize()
        driver.manuallyLogConfigExposure(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
        assertEquals("true", events[0].eventMetadata?.get("isManualExposure") ?: "")
    }

    @Test
    fun testGetExperimentWithExposureLoggingDisabled() = runBlocking {
        driver.initialize()
        driver.getExperimentWithExposureLoggingDisabled(user, "a_config")
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }
    @Test
    fun testGetExperimentWithExposureLoggingEnabled() = runBlocking {
        driver.initialize()
        driver.getExperiment(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetLayerWithExposureLoggingDisabled() = runBlocking {
        driver.initialize()
        val layer = driver.getLayerWithExposureLoggingDisabled(user, "explicit_vs_implicit_parameter_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }
    @Test
    fun testGetLayerWithExposureLoggingEnabled() = runBlocking {
        driver.initialize()
        val layer = driver.getLayer(user, "explicit_vs_implicit_parameter_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }
}
