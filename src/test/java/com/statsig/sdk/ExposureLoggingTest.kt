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
import org.junit.Before
import org.junit.Test

class ExposureLoggingTest {
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

        val mockGateResponse = APIFeatureGate("a_gate", true, "ruleID")
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
                        val logBody = request.body.readUtf8()
                        eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput::class.java))
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

        driver = StatsigServer.create()
    }

    @Test
    fun testManualLogLayerParameterExposure() = runBlocking {
        driver.initialize("secret-local", options)
        driver.manuallyLogLayerParameterExposure(user, "a_layer", "a_param")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
        assertEquals(events[0].eventMetadata?.get("isManualExposure") ?: "", "true")
    }

    @Test
    fun testCheckGateWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        driver.checkGateWithExposureLoggingDisabled(user, "a_gate")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testCheckGateSyncWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = CheckGateOptions(true)
        driver.checkGateSync(user, "a_gate", setExposureLoggingDisabled)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testCheckGateSyncWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = CheckGateOptions(false)
        driver.checkGateSync(user, "a_gate", setExposureLoggingDisabled) // default
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testCheckGateSyncWithExposureLoggingDefault() = runBlocking {
        driver.initialize("secret-local", options)
        driver.checkGateSync(user, "a_gate") // default
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testManuallyLogGateExposure() = runBlocking {
        driver.initialize("secret-local", options)
        driver.manuallyLogGateExposure(user, "a_gate")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
        assertEquals(events[0].eventMetadata?.get("isManualExposure") ?: "", "true")
    }

    @Test
    fun testCheckGateWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        driver.checkGate(user, "always_on_gate")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetConfigWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        driver.getConfigWithExposureLoggingDisabled(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testGetConfigWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        driver.getConfig(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetConfigSyncWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = GetConfigOptions(true)
        driver.getConfigSync(user, "a_config", setExposureLoggingDisabled)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testGetConfigSyncWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = GetConfigOptions(false)
        driver.getConfigSync(user, "a_config", setExposureLoggingDisabled)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetConfigSyncWithExposureLoggingDefault() = runBlocking {
        driver.initialize("secret-local", options)
        driver.getConfigSync(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testManualLogConfigExposure() = runBlocking {
        driver.initialize("secret-local", options)
        driver.manuallyLogConfigExposure(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
        assertEquals("true", events[0].eventMetadata?.get("isManualExposure") ?: "")
    }

    @Test
    fun testGetExperimentWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        driver.getExperimentWithExposureLoggingDisabled(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testGetExperimentWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        driver.getExperiment(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetExperimentSyncWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = GetExperimentOptions(true)
        driver.getExperimentSync(user, "a_config", setExposureLoggingDisabled)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testGetExperimentSyncWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = GetExperimentOptions(false)
        driver.getExperimentSync(user, "a_config", setExposureLoggingDisabled)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetExperimentSyncWithExposureLoggingDefault() = runBlocking {
        driver.initialize("secret-local", options)
        driver.getExperimentSync(user, "a_config")
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetLayerWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        val layer = driver.getLayerWithExposureLoggingDisabled(user, "explicit_vs_implicit_parameter_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testGetLayerWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        val layer = driver.getLayer(user, "explicit_vs_implicit_parameter_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetLayerSyncWithExposureLoggingDisabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = GetLayerOptions(true)
        val layer = driver.getLayerSync(user, "explicit_vs_implicit_parameter_layer", setExposureLoggingDisabled)
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(0, events.size)
    }

    @Test
    fun testGetLayerSyncWithExposureLoggingEnabled() = runBlocking {
        driver.initialize("secret-local", options)
        val setExposureLoggingDisabled = GetLayerOptions(false)
        val layer = driver.getLayerSync(user, "explicit_vs_implicit_parameter_layer", setExposureLoggingDisabled)
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }

    @Test
    fun testGetLayerSyncWithExposureLoggingDefault() = runBlocking {
        driver.initialize("secret-local", options)
        val layer = driver.getLayerSync(user, "explicit_vs_implicit_parameter_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents(eventLogInputCompletable)
        assertEquals(1, events.size)
    }
}
