package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test

private const val TEST_TIMEOUT = 10L

class LayerExposureTest {
    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions

    private lateinit var user: StatsigUser
    private lateinit var driver: StatsigServer

    @Before
    fun setup() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        eventLogInputCompletable = CompletableDeferred()
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/layer_exposure_download_config_specs.json")?.readText() ?: ""
        user = StatsigUser("dan")
        server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    when (request.path) {
                        "/v1/download_config_specs" -> {
                            if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                                throw Exception("No content type set!")
                            }
                            return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                        }
                        "/v1/log_event" -> {
                            val logBody = request.body.readUtf8()
                            if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                                throw Exception("No content type set!")
                            }
                            eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput::class.java))
                            return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                        }
                    }

                    return MockResponse().setResponseCode(404)
                }
            }
        }

        val options = StatsigOptions().apply {
            api = server.url("/v1").toString()
        }
        driver = StatsigServer.create("secret-testcase", options)
    }

    @Test
    fun testDoesNotLogOnGetLayer() = runBlocking {
        driver.initialize()
        driver.getLayer(user, "unallocated_layer")
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }

    @Test
    fun testDoesNotLogOnInvalidType() = runBlocking {
        driver.initialize()
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getString("an_int", "err")
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }

    @Test
    fun testDoesNotLogNonExistentKeys() = runBlocking {
        driver.initialize()
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getString("a_string", "err")
        driver.shutdown()

        assertFalse("should not have called log_event endpoint", eventLogInputCompletable.isCompleted)
    }

    @Test
    fun testUnallocatedLayerLogging() = runBlocking {
        driver.initialize()
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents()
        assertEquals(1, events.size)
        assertEquals(
            Gson().toJson(
                mapOf(
                    "config" to "unallocated_layer",
                    "ruleID" to "default",
                    "allocatedExperiment" to "",
                    "parameterName" to "an_int",
                    "isExplicitParameter" to "false"
                )
            ),
            Gson().toJson(events[0].eventMetadata)
        )
    }

    @Test
    fun testExplicitVsImplicitParameterLogging() = runBlocking {
        driver.initialize()
        val layer = driver.getLayer(user, "explicit_vs_implicit_parameter_layer")
        stutter { layer.getInt("an_int", 0) }
        stutter { layer.getString("a_string", "err") }
        driver.shutdown()

        val events = captureEvents()
        assertEquals(2, events.size)

        assertEquals(
            Gson().toJson(
                mapOf(
                    "config" to "explicit_vs_implicit_parameter_layer",
                    "ruleID" to "alwaysPass",
                    "allocatedExperiment" to "experiment",
                    "parameterName" to "an_int",
                    "isExplicitParameter" to "true"
                )
            ),
            Gson().toJson(events[0].eventMetadata)
        )
        assertEquals(
            Gson().toJson(
                mapOf(
                    "config" to "explicit_vs_implicit_parameter_layer",
                    "ruleID" to "alwaysPass",
                    "allocatedExperiment" to "",
                    "parameterName" to "a_string",
                    "isExplicitParameter" to "false"
                )
            ),
            Gson().toJson(events[1].eventMetadata)
        )
    }

    @Test
    fun testDifferentObjectTypeLogging() = runBlocking {
        driver.initialize()
        val layer = driver.getLayer(user, "different_object_type_logging_layer")
        stutter { layer.getBoolean("a_bool", false) }
        stutter { layer.getInt("an_int", 0) }
        stutter { layer.getDouble("a_double", 0.0) }
        stutter { layer.getLong("a_long", 0L) }
        stutter { layer.getString("a_string", "err") }
        stutter { layer.getArray("an_array", arrayOf<Any>()) }
        stutter { layer.getDictionary("an_object", mapOf()) }
        stutter { layer.getConfig("another_object") }
        driver.shutdown()

        val events = captureEvents()
        assertEquals(8, events.size)

        assertEquals("a_bool", events[0].eventMetadata?.get("parameterName"))
        assertEquals("an_int", events[1].eventMetadata?.get("parameterName"))
        assertEquals("a_double", events[2].eventMetadata?.get("parameterName"))
        assertEquals("a_long", events[3].eventMetadata?.get("parameterName"))
        assertEquals("a_string", events[4].eventMetadata?.get("parameterName"))
        assertEquals("an_array", events[5].eventMetadata?.get("parameterName"))
        assertEquals("an_object", events[6].eventMetadata?.get("parameterName"))
        assertEquals("another_object", events[7].eventMetadata?.get("parameterName"))
    }

    @Test
    fun testLogsUserAndEventName() = runBlocking {
        val user = StatsigUser("dloomb")
        user.email = "dan@statsigly.com"

        driver.initialize()
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents()
        assertEquals(1, events.size)

        assertEquals(
            Gson().toJson(mapOf("userID" to "dloomb", "email" to "dan@statsigly.com")),
            Gson().toJson(events[0].user)
        )
        assertEquals("statsig::layer_exposure", events[0].eventName)
    }

    /***
     * Used to ensure event logs have different timestamps
     */
    private fun stutter(action: () -> Unit) {
        action()
        Thread.sleep(1)
    }

    private fun captureEvents(): Array<StatsigEvent> = runBlocking {
        val logs = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }

        logs.events.sortBy { it.time }
        return@runBlocking logs.events
    }
}