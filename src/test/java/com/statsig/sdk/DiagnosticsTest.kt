package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.lang.StringBuilder

class DiagnosticsTest {
    lateinit var driver: StatsigServer
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var logEvents: Array<StatsigEvent>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions
    private lateinit var gson: Gson
    lateinit var downloadConfigSpecsResponse: String

    private val user = StatsigUser("testUser")

    @JvmField
    @Rule
    val retry = RetryRule(3)

    @Before
    fun setup() {
        downloadConfigSpecsResponse = DiagnosticsTest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
        server = MockWebServer()
        server.start(8899)
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
        eventLogInputCompletable = CompletableDeferred()
        logEvents = arrayOf()
    }

    @After
    fun afterEach() {
        driver.shutdown()
        server.shutdown()
    }

    @Test
    fun testInitialize() = runBlocking {
        setupWebServer(downloadConfigSpecsResponse)
        driver.initializeAsync("secret-testcase", options).get()
        driver.shutdown()
        logEvents = TestUtil.captureEvents(eventLogInputCompletable, false)
        val diagnosticsEvent = logEvents.find { it.eventName == "statsig::diagnostics" }
        val markers: List<Marker> = gson.fromJson(diagnosticsEvent!!.eventMetadata!!["markers"], object : TypeToken<List<Marker>>() {}.type)
        Assert.assertEquals(8, markers.size)
        verifyMarker(markers[0], Marker(key = KeyType.OVERALL, action = ActionType.START))
        verifyMarker(markers[1], Marker(key = KeyType.DOWNLOAD_CONFIG_SPECS, action = ActionType.START, step = StepType.NETWORK_REQUEST))
        verifyMarker(markers[2], Marker(key = KeyType.DOWNLOAD_CONFIG_SPECS, action = ActionType.END, step = StepType.NETWORK_REQUEST))
        verifyMarker(markers[3], Marker(key = KeyType.DOWNLOAD_CONFIG_SPECS, action = ActionType.START, step = StepType.PROCESS))
        verifyMarker(markers[4], Marker(key = KeyType.DOWNLOAD_CONFIG_SPECS, action = ActionType.END, step = StepType.PROCESS))
        verifyMarker(markers[5], Marker(key = KeyType.GET_ID_LIST_SOURCES, action = ActionType.START, step = StepType.NETWORK_REQUEST))
        verifyMarker(markers[6], Marker(key = KeyType.GET_ID_LIST_SOURCES, action = ActionType.END, step = StepType.NETWORK_REQUEST))
        verifyMarker(markers[7], Marker(key = KeyType.OVERALL, action = ActionType.END))
        assertEquals(Gson().toJson(options.getLoggingCopy()), diagnosticsEvent!!.eventMetadata!!["statsigOptions"])
    }

    @Test
    fun testCoreAPI() = runBlocking {
        val downloadConfigSpecsResponseWithSampling = StringBuilder(downloadConfigSpecsResponse).insert(downloadConfigSpecsResponse.length - 2, ",\n \"diagnostics\": {\"initialize\": \"0\", \"api_call\": \"10000\"}").toString()
        setupWebServer(downloadConfigSpecsResponseWithSampling)
        driver.initializeAsync("secret-testcase", options).get()
        driver.checkGate(user, "always_on_gate")
        driver.getConfig(user, "test_config")
        driver.getExperiment(user, "sample_experiment")
        driver.getLayer(user, "a_layer")
        driver.shutdown()
        logEvents = TestUtil.captureEvents(eventLogInputCompletable, false)
        val diagnosticsEvent = logEvents.find { it.eventName == "statsig::diagnostics" }
        val markers: List<Marker> = gson.fromJson(diagnosticsEvent!!.eventMetadata!!["markers"], object : TypeToken<List<Marker>>() {}.type)
        assertEquals(8, markers.size)
        verifyMarker(
            markers[0],
            Marker(
                markerID = "CHECK_GATE_0",
                action = ActionType.START,
                configName = "always_on_gate",
                key = KeyType.CHECK_GATE,
            ),
        )
        verifyMarker(
            markers[1],
            Marker(
                markerID = "CHECK_GATE_0",
                action = ActionType.END,
                configName = "always_on_gate",
                key = KeyType.CHECK_GATE,
            ),
        )
        verifyMarker(
            markers[2],
            Marker(
                markerID = "GET_CONFIG_2",
                action = ActionType.START,
                configName = "test_config",
                key = KeyType.GET_CONFIG,
            ),
        )
        verifyMarker(
            markers[3],
            Marker(
                markerID = "GET_CONFIG_2",
                action = ActionType.END,
                configName = "test_config",
                key = KeyType.GET_CONFIG,
            ),
        )
        verifyMarker(
            markers[4],
            Marker(
                markerID = "GET_EXPERIMENT_4",
                action = ActionType.START,
                configName = "sample_experiment",
                key = KeyType.GET_EXPERIMENT,
            ),
        )
        verifyMarker(
            markers[5],
            Marker(
                markerID = "GET_EXPERIMENT_4",
                action = ActionType.END,
                configName = "sample_experiment",
                key = KeyType.GET_EXPERIMENT,
            ),
        )
        verifyMarker(
            markers[6],
            Marker(
                markerID = "GET_LAYER_6",
                action = ActionType.START,
                configName = "a_layer",
                key = KeyType.GET_LAYER,
            ),
        )
        verifyMarker(
            markers[7],
            Marker(
                markerID = "GET_LAYER_6",
                action = ActionType.END,
                configName = "a_layer",
                key = KeyType.GET_LAYER,
            ),
        )
    }

    @Test
    fun testSampling() = runBlocking {
        val downloadConfigSpecsResponseWithSampling = StringBuilder(downloadConfigSpecsResponse).insert(downloadConfigSpecsResponse.length - 2, ",\n \"diagnostics\": {\"initialize\": \"0\", \"api_call\": \"0\"}").toString()
        setupWebServer(downloadConfigSpecsResponseWithSampling)
        driver.initializeAsync("secret-testcase", options).get()
        driver.shutdown()
        Assert.assertFalse(
            "should not have called log_event endpoint",
            eventLogInputCompletable.isCompleted,
        )
    }

    @Test
    fun testDisableDiagnostics() = runBlocking {
        val downloadConfigSpecsResponseWithSampling = StringBuilder(downloadConfigSpecsResponse).insert(downloadConfigSpecsResponse.length - 2, ",\n \"diagnostics\": {\"initialize\": \"10000\", \"api_call\": \"0\"}").toString()
        setupWebServer(downloadConfigSpecsResponseWithSampling)
        options.disableDiagnostics = true
        driver.initializeAsync("secret-testcase", options).get()
        driver.checkGate(user, "always_on_gate")
        driver.shutdown()
        logEvents = TestUtil.captureEvents(eventLogInputCompletable, false)
        val diagnosticsEvents = logEvents.filter { it.eventName == "statsig::diagnostics" }
        // Only log initialize
        assertEquals(1, diagnosticsEvents.size)
        assertEquals("initialize", logEvents[0]!!.eventMetadata!!["context"])
        val diagnostics = getDiagnostics()
        assert(diagnostics.markers[ContextType.CONFIG_SYNC].isNullOrEmpty())
        assert(diagnostics.markers[ContextType.API_CALL].isNullOrEmpty())
        assert(diagnostics.markers[ContextType.INITIALIZE].isNullOrEmpty())
    }

    @Test
    fun testConcurrentCoreApiCall() = runBlocking {
        val downloadConfigSpecsResponseWithSampling = StringBuilder(downloadConfigSpecsResponse).insert(downloadConfigSpecsResponse.length - 2, ",\n \"diagnostics\": {\"initialize\": \"0\", \"api_call\": \"10000\"}").toString()
        setupWebServer(downloadConfigSpecsResponseWithSampling)
        driver.initializeAsync("secret-testcase", options).get()
        val threads = arrayListOf<Thread>()
        val threadSize = 5
        val iterations = 5
        for (i in 1..threadSize) {
            val t =
                Thread {
                    for (j in 1..iterations) {
                        val user = StatsigUser("user_id_$i")
                        user.email = "testuser@statsig.com"

                        runBlocking {
                            driver.checkGate(user, "always_on_gate")
                        }
                    }
                }
            threads.add(t)
        }
        for (t in threads) {
            t.start()
        }
        for (t in threads) {
            t.join()
        }
        driver.shutdown()
        logEvents = TestUtil.captureEvents(eventLogInputCompletable, false)
        val diagnosticsEvents = logEvents.filter { it.eventName == "statsig::diagnostics" && it.eventMetadata?.get("context") == "api_call" }
        val markers = mutableListOf<Marker>()
        diagnosticsEvents.forEach {
            val temp: List<Marker> = gson.fromJson(it.eventMetadata!!["markers"], object : TypeToken<List<Marker>>() {}.type)
            markers.addAll(temp)
        }
        assert(markers.size === threadSize * iterations * 2)
    }

    @Test
    fun testDiagnosticsMarkerSize() = runBlocking {
        val downloadConfigSpecsResponseWithSampling = StringBuilder(downloadConfigSpecsResponse).insert(downloadConfigSpecsResponse.length - 2, ",\n \"diagnostics\": {\"initialize\": \"0\", \"api_call\": \"10000\"}").toString()
        setupWebServer(downloadConfigSpecsResponseWithSampling)
        val user = StatsigUser("user_id_1")
        driver.initializeAsync("secret-testcase", options).get()
        for (i in 1..300) {
            driver.checkGate(user, "always_on_gate")
        }
        driver.shutdown()
        logEvents = TestUtil.captureEvents(eventLogInputCompletable, false)
        val diagnosticsEvents = logEvents.filter { it.eventName == "statsig::diagnostics" && it.eventMetadata?.get("context") == "api_call" }
        val markers = mutableListOf<Marker>()
        diagnosticsEvents.forEach {
            val temp: List<Marker> = gson.fromJson(it.eventMetadata!!["markers"], object : TypeToken<List<Marker>>() {}.type)
            markers.addAll(temp)
        }
        assert(markers.size === 50)
    }

    @Test
    fun testContextBeingClear() = runBlocking {
        setupWebServer(downloadConfigSpecsResponse)
        options.rulesetsSyncIntervalMs = 50
        options.idListsSyncIntervalMs = 50
        driver.initializeAsync("secret-testcase", options).get()
        val diagnostics = getDiagnostics()
        assert(diagnostics.markers[ContextType.INITIALIZE]!!.size == 0) // Ensure initialize markers are cleared
        // Wait for config sync happen
        delay(100)
        // Ensure config sync markers are cleared
        assert((diagnostics.markers[ContextType.CONFIG_SYNC]?.size ?: 0) == 0)
        driver.checkGate(StatsigUser("testUser"), "always_on_gate")
        driver.checkGate(StatsigUser("testUser"), "always_off_gate")
        driver.getClientInitializeResponse(StatsigUser("testUser"))
        val logger = getLogger()
        logger.flush()
        assert(diagnostics.markers[ContextType.API_CALL]!!.size == 0) // Ensure api call markers are cleared
        assert(diagnostics.markers[ContextType.GET_CLIENT_INITIALIZE_RESPONSE]!!.size == 0) // Ensure api call markers are cleared
    }

    private fun getLogger(): StatsigLogger {
        val loggerField = driver.javaClass.getDeclaredField("logger")
        loggerField.isAccessible = true
        return loggerField[driver] as StatsigLogger
    }

    private fun getDiagnostics(): Diagnostics {
        val diagnosticsField = driver.javaClass.getDeclaredField("diagnostics")
        diagnosticsField.isAccessible = true
        return diagnosticsField[driver] as Diagnostics
    }

    private fun setupWebServer(downLoadConfigResponse: String) {
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        println("dcs")
                        return MockResponse().setResponseCode(200)
                            .setBody(downLoadConfigResponse)
                    }
                    if ("/v1/log_event" in request.path!!) {
                        val response = TestUtil.mockLogEventEndpoint(request, eventLogInputCompletable)
//                        val input = gson.fromJson(logBody, LogEventInput::class.java)
//                        logEvents.addAll(eventLogInputCompletable.events)
                        return response
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
            options = StatsigOptions().apply {
                api = server.url("/v1").toString()
            }
            driver = StatsigServer.create()
        }
    }

    private fun verifyMarker(actual: Marker, expected: Marker) {
        Assert.assertEquals(expected.key, actual.key)
        Assert.assertEquals(expected.action, actual.action)
        Assert.assertEquals(expected.step, actual.step)
        Assert.assertEquals(expected.configName, actual.configName)
        Assert.assertEquals(expected.markerID, actual.markerID)
    }
}
