package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import io.mockk.every
import io.mockk.mockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TIME_NOW_MOCK: Long = 1234567890L

class EvaluationDetailsTest {

    private val configSpecsResponse =
        StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
    private val gson: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
    private lateinit var driver: StatsigServer
    private lateinit var specStore: SpecStore
    val user = StatsigUser("a-user")
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer

    @Before
    fun setup() = runBlocking {
        mockkObject(Utils.Companion)

        every { Utils.getTimeInMillis() } returns TIME_NOW_MOCK

        eventLogInputCompletable = CompletableDeferred()
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""

        server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
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

        val options = StatsigOptions().apply {
            api = server.url("/v1").toString()
            disableDiagnostics = true
        }

        driver = StatsigServer.create()
        driver.initialize("secret-local", options)
    }

    @Test
    fun uninitializedTest() = runBlocking {
        specStore = TestUtilJava.getSpecStoreFromStatsigServer(driver)
        TestUtilJava.setInitReasonFromSpecStore(specStore, EvaluationReason.UNINITIALIZED)

        driver.checkGate(user, "always_on_gate")
        driver.getConfig(user, "test_config")
        driver.getExperiment(user, "sample_experiment")
        var layer = driver.getLayer(user, "a_layer")
        layer.getString("experiment_param", "fallback_value") // should not log

        driver.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)

        assertEquals(3, events.size)
        assertEventEqual(
            events[0],
            mapOf(
                "eventName" to "statsig::gate_exposure",
                "reason" to EvaluationReason.UNINITIALIZED.toString(),
            ),
            true,
        )
        assertEventEqual(
            events[1],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.UNINITIALIZED.toString(),
            ),
            true,
        )
        assertEventEqual(
            events[2],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.UNINITIALIZED.toString(),
            ),
            true,
        )
    }

    @Test
    fun networkTest() = runBlocking {
        driver.checkGate(user, "always_on_gate")
        driver.getConfig(user, "test_config")
        driver.getExperiment(user, "sample_experiment")
        var layer = driver.getLayer(user, "a_layer")
        layer.getString("experiment_param", "fallback_value")

        driver.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)
        print(events)

        assertEquals(4, events.size)

        assertEventEqual(
            events[0],
            mapOf(
                "eventName" to "statsig::gate_exposure",
                "reason" to EvaluationReason.NETWORK.toString(),
            ),
        )
        assertEventEqual(
            events[1],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.NETWORK.toString(),
            ),
        )
        assertEventEqual(
            events[2],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.NETWORK.toString(),
            ),
        )
        assertEventEqual(
            events[3],
            mapOf(
                "eventName" to "statsig::layer_exposure",
                "reason" to EvaluationReason.NETWORK.toString(),
            ),
        )
    }

    @Test
    fun bootstrapTest() = runBlocking {
        val options = StatsigOptions(bootstrapValues = configSpecsResponse, api = server.url("/v1").toString(), disableDiagnostics = true)
        val bootstrapServer = StatsigServer.create()
        bootstrapServer.initialize("secret-key", options)

        bootstrapServer.checkGate(user, "always_on_gate")
        bootstrapServer.getConfig(user, "test_config")
        bootstrapServer.getExperiment(user, "sample_experiment")
        var layer = bootstrapServer.getLayer(user, "a_layer")
        layer.getString("experiment_param", "fallback_value")

        bootstrapServer.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)

        events.forEach {
            println(it)
        }

        assertEquals(4, events.size)
        assertEventEqual(
            events[0],
            mapOf(
                "eventName" to "statsig::gate_exposure",
                "reason" to EvaluationReason.BOOTSTRAP.toString(),
            ),
        )
        assertEventEqual(
            events[1],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.BOOTSTRAP.toString(),
            ),
        )
        assertEventEqual(
            events[2],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.BOOTSTRAP.toString(),
            ),
        )
        assertEventEqual(
            events[3],
            mapOf(
                "eventName" to "statsig::layer_exposure",
                "reason" to EvaluationReason.BOOTSTRAP.toString(),
            ),
        )
    }

    @Test
    fun localOverrideTest() = runBlocking {
        driver.overrideGate("always_on_gate", false)
        driver.overrideConfig("sample_experiment", emptyMap())

        driver.checkGate(user, "always_on_gate")
        driver.getConfig(user, "sample_experiment")

        driver.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)

        events.forEach {
            println(it)
        }
        assertEquals(2, events.size)
        assertEventEqual(
            events[0],
            mapOf(
                "eventName" to "statsig::gate_exposure",
                "reason" to EvaluationReason.LOCAL_OVERRIDE.toString(),
            ),
        )
        assertEventEqual(
            events[1],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.LOCAL_OVERRIDE.toString(),
            ),
        )
    }

    @Test
    fun unrecognizedTest() = runBlocking {
        driver.checkGate(user, "not_a_gate")
        driver.getConfig(user, "not_a_config")
        driver.getExperiment(user, "not_a_experiment")
        var layer = driver.getLayer(user, "not_a_layer")
        layer.getString("a_param", "a_default") // should not log

        driver.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)

        assertEquals(3, events.size)
        assertEventEqual(
            events[0],
            mapOf(
                "eventName" to "statsig::gate_exposure",
                "reason" to EvaluationReason.UNRECOGNIZED.toString(),
            ),
        )
        assertEventEqual(
            events[1],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.UNRECOGNIZED.toString(),
            ),
        )
        assertEventEqual(
            events[2],
            mapOf(
                "eventName" to "statsig::config_exposure",
                "reason" to EvaluationReason.UNRECOGNIZED.toString(),
            ),
        )
    }

    @Test
    fun loggingTest() = runBlocking {
        driver.checkGate(user, "always_on_gate")
        driver.getConfig(user, "test_config")
        driver.getExperiment(user, "sample_experiment")
        var layer = driver.getLayer(user, "a_layer")
        layer.getString("experiment_param", "fallback_value")

        driver.shutdown()
        val events = TestUtil.captureEvents(eventLogInputCompletable)

        assertEquals(4, events.size)
    }
}

internal fun assertEventEqual(event: StatsigEvent, expected: Map<Any, Any>, skip_sync_times: Boolean = false) {
    assertEquals(expected["eventName"], event.eventName)
    assertEquals(expected["reason"], event.eventMetadata?.get(("reason")))

    val serverTime = event.eventMetadata?.get("serverTime")?.toLong()
    assertTrue(serverTime != null && serverTime > 0)

    if (!skip_sync_times) {
        val configSyncTime = event.eventMetadata?.get("configSyncTime")?.toLong()
        val initTime = event.eventMetadata?.get("initTime")?.toLong()

        assertNotNull(configSyncTime)
        assertNotNull(initTime)

        assertEquals(1631638014811, configSyncTime)
        assertEquals(1631638014811, initTime)
    } else {
        assertEquals("0", event.eventMetadata?.get("configSyncTime"))
        assertEquals("0", event.eventMetadata?.get("initTime"))
    }
}
