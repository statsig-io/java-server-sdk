package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.lang.StringBuilder

class DiagnosticsTest {
    lateinit var driver: StatsigServer
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions
    private lateinit var gson: Gson
    lateinit var downloadConfigSpecsResponse: String

    @Before
    fun setup() {
        downloadConfigSpecsResponse = StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
        server = MockWebServer()
        server.start(8899)
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
        eventLogInputCompletable = CompletableDeferred()
    }

    @After
    fun afterEach() {
        server.shutdown()
    }

    @Test
    fun testInitialize() = runBlocking {
        setupWebServer(downloadConfigSpecsResponse)
        driver.initializeAsync("secret-testcase", options).get()
        driver.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)
        val diagnosticsEvent = events.find { it.eventName == "statsig::diagnostics" }
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
    }

    @Test
    fun testSamping() = runBlocking {
        val downloadConfigSpecsResponseWithSampling = StringBuilder(downloadConfigSpecsResponse).insert(downloadConfigSpecsResponse.length - 2, ",\n \"diagnostics\": {\"initialize\": \"0\"}").toString()
        setupWebServer(downloadConfigSpecsResponseWithSampling)
        driver.initializeAsync("secret-testcase", options).get()
        driver.shutdown()
        Assert.assertFalse(
            "should not have called log_event endpoint",
            eventLogInputCompletable.isCompleted,
        )
    }

    private fun setupWebServer(downLoadConfigResponse: String) {
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200)
                            .setBody(downLoadConfigResponse)
                    }
                    if ("/v1/log_event" in request.path!!) {
                        val logBody = request.body.readUtf8()
                        eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput::class.java))
                        return MockResponse().setResponseCode(200)
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
    }
}
