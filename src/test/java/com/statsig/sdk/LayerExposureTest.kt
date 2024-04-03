import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.statsig.sdk.LogEventInput
import com.statsig.sdk.StatsigE2ETest
import com.statsig.sdk.StatsigEvent
import com.statsig.sdk.StatsigOptions
import com.statsig.sdk.StatsigServer
import com.statsig.sdk.StatsigUser
import com.statsig.sdk.TestUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TIME_NOW_MOCK: Long = 1234567890L
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
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
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
    fun testDoesNotLogOnGetLayer() = runBlocking {
        driver.initialize("secret-testcase", options)
        driver.getLayer(user, "unallocated_layer")
        driver.shutdown()
        val events = captureEvents()

        assertEquals(events.size, 0)
    }

    @Test
    fun testDoesNotLogOnInvalidType() = runBlocking {
        driver.initialize("secret-testcase", options)
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getString("an_int", "err")
        driver.shutdown()
        val events = captureEvents()

        assertEquals(events.size, 0)
    }

    @Test
    fun testDoesNotLogNonExistentKeys() = runBlocking {
        driver.initialize("secret-testcase", options)
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getString("a_string", "err")
        driver.shutdown()
        val events = captureEvents()

        assertEquals(events.size, 0)
    }

    @Test
    fun testUnallocatedLayerLogging() = runBlocking {
        driver.initialize("secret-testcase", options)
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents()
        assertEquals(1, events.size)

        val serverTime = events[0].eventMetadata?.get("serverTime")?.toLong()
        assertTrue(serverTime != null && serverTime > 0)

        val metadata = events[0].eventMetadata?.toMutableMap() ?: mutableMapOf()
        metadata["serverTime"] = TIME_NOW_MOCK.toString()
        assertEquals(
            Gson().toJson(
                mapOf(
                    "config" to "unallocated_layer",
                    "ruleID" to "default",
                    "allocatedExperiment" to "",
                    "parameterName" to "an_int",
                    "isExplicitParameter" to "false",
                    "isManualExposure" to "false",
                    "reason" to "NETWORK",
                    "configSyncTime" to "0",
                    "initTime" to "-1",
                    "serverTime" to TIME_NOW_MOCK.toString(),
                ),
            ),
            Gson().toJson(metadata),
        )
    }

    @Test
    fun testExplicitVsImplicitParameterLogging() = runBlocking {
        driver.initialize("secret-testcase", options)
        val layer = driver.getLayer(user, "explicit_vs_implicit_parameter_layer")
        stutter { layer.getInt("an_int", 0) }
        stutter { layer.getString("a_string", "err") }
        driver.shutdown()

        val events = captureEvents()
        assertEquals(2, events.size)

        var serverTime = events[0].eventMetadata?.get("serverTime")?.toLong()
        assertTrue(serverTime != null && serverTime > 0)

        var metadata = events[0].eventMetadata?.toMutableMap() ?: mutableMapOf()
        metadata["serverTime"] = TIME_NOW_MOCK.toString()

        assertEquals(
            Gson().toJson(
                mapOf(
                    "config" to "explicit_vs_implicit_parameter_layer",
                    "ruleID" to "alwaysPass",
                    "allocatedExperiment" to "experiment",
                    "parameterName" to "an_int",
                    "isExplicitParameter" to "true",
                    "isManualExposure" to "false",
                    "reason" to "NETWORK",
                    "configSyncTime" to "0",
                    "initTime" to "-1",
                    "serverTime" to TIME_NOW_MOCK.toString(),
                ),
            ),
            Gson().toJson(metadata),
        )

        serverTime = events[1].eventMetadata?.get("serverTime")?.toLong()
        assertTrue(serverTime != null && serverTime > 0)
        metadata = events[1].eventMetadata?.toMutableMap() ?: mutableMapOf()
        metadata["serverTime"] = TIME_NOW_MOCK.toString()

        assertEquals(
            Gson().toJson(
                mapOf(
                    "config" to "explicit_vs_implicit_parameter_layer",
                    "ruleID" to "alwaysPass",
                    "allocatedExperiment" to "",
                    "parameterName" to "a_string",
                    "isExplicitParameter" to "false",
                    "isManualExposure" to "false",
                    "reason" to "NETWORK",
                    "configSyncTime" to "0",
                    "initTime" to "-1",
                    "serverTime" to TIME_NOW_MOCK.toString(),

                ),
            ),
            Gson().toJson(metadata),
        )
    }

    @Test
    fun testDifferentObjectTypeLogging() = runBlocking {
        driver.initialize("secret-testcase", options)
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

        driver.initialize("secret-testcase", options)
        val layer = driver.getLayer(user, "unallocated_layer")
        layer.getInt("an_int", 0)
        driver.shutdown()

        val events = captureEvents()
        assertEquals(1, events.size)

        assertEquals(
            Gson().toJson(mapOf("userID" to "dloomb", "email" to "dan@statsigly.com")),
            Gson().toJson(events[0].user),
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
        logs.events = logs.events.filter { it.eventName != "statsig::diagnostics" }.toTypedArray()
        logs.events.sortBy { it.time }
        return@runBlocking logs.events
    }
}
