package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test

private data class LogEventInput(
    @SerializedName("events") val events: Array<StatsigEvent>,
)

private const val TEST_TIMEOUT = 10L

/**
 * There are 2 mock gates, 1 mock config, and 1 mock experiment
 * always_on_gate has a single group, Everyone 100%
 * on_for_statsig_email has a single group, email contains "@statsig.com" 100%
 * test_config has a single group, email contains "@statsig.com" 100%
 * - passing returns {number: 7, string: "statsig", boolean: false}
 * - failing (default) returns {number: 4, string: "default", boolean: true}
 * sample_experiment is a 50/50 experiment with a single parameter, experiment_param
 * - ("test" or "control" depending on the user's group)
 */
class StatsigE2ETest {

    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions

    private lateinit var statsigUser: StatsigUser
    private lateinit var randomUser: StatsigUser
    private lateinit var driver: StatsigServer

    private var download_config_count: Int = 0
    private var download_id_list_count: Int = 0
    private var download_list_1_count: Int = 0
    private var download_list_2_count: Int = 0

    @Before
    fun setup() {
        gson = Gson()

        eventLogInputCompletable = CompletableDeferred()
        val downloadConfigSpecsResponse = StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
        
        server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    when (request.path) {
                        "/v1/download_config_specs" -> {
                            download_config_count++
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
                        "/v1/get_id_lists" -> {
                            download_id_list_count++
                            if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                                throw Exception("No content type set!")
                            }
                            var list: Map<String, Any>
                            if (download_id_list_count == 1) {
                                list = mapOf(
                                    "list_1" to mapOf(
                                        "name" to "list_1",
                                        "size" to 6,
                                        "creationTime" to 1,
                                        "url" to server.url("/v1/list_1").toString(),
                                        "fileID" to "file_id_1",
                                    ),
                                    "list_2" to mapOf(
                                        "name" to "list_2",
                                        "size" to 6,
                                        "creationTime" to 1,
                                        "url" to server.url("/v1/list_2").toString(),
                                        "fileID" to "file_id_2",
                                    )
                                )
                            } else if (download_id_list_count == 2) {
                                list = mapOf(
                                    "list_1" to mapOf(
                                        "name" to "list_1",
                                        "size" to 15,
                                        "creationTime" to 1,
                                        "url" to server.url("/v1/list_1").toString(),
                                        "fileID" to "file_id_1",
                                    ),
                                    "list_2" to mapOf(
                                        "name" to "list_2",
                                        "size" to 18,
                                        "creationTime" to 1,
                                        "url" to server.url("/v1/list_2").toString(),
                                        "fileID" to "file_id_2",
                                    )
                                )
                            } else if (download_id_list_count == 3) {
                                list = mapOf(
                                    "list_1" to mapOf(
                                        "name" to "list_1",
                                        "size" to 18,
                                        "creationTime" to 1,
                                        "url" to server.url("/v1/list_1").toString(),
                                        "fileID" to "file_id_1",
                                    ),
                                    "list_2" to mapOf(
                                        "name" to "list_2",
                                        "size" to 6,
                                        "creationTime" to 2,
                                        "url" to server.url("/v1/list_2").toString(),
                                        "fileID" to "file_id_2_a",
                                    )
                                )
                            } else {
                                list = mapOf(
                                    "list_1" to mapOf(
                                        "name" to "list_1",
                                        "size" to 18,
                                        "creationTime" to 1,
                                        "url" to server.url("/v1/list_1").toString(),
                                        "fileID" to "file_id_1",
                                    ),
                                    "list_2" to mapOf(
                                        "name" to "list_2",
                                        "size" to 9,
                                        "creationTime" to 2,
                                        "url" to server.url("/v1/list_2").toString(),
                                        "fileID" to "file_id_2_a",
                                    )
                                )
                            }
                            return MockResponse().setResponseCode(200).setBody(gson.toJson(list))
                        }
                        "/v1/list_1" -> {
                            val range = request.headers["range"]
                            val startIndex = range!!.substring(6, range!!.length-1).toIntOrNull()
                            download_list_1_count++

                            var content: String = when (download_list_1_count) {
                                1 -> "+1\r+2\r"
                                2 -> "+1\r+2\r+3\r+4\r-1\r"
                                3 -> "+1\r+2\r+3\r+4\r-1\r?5\r" // append invalid entry, should reset the list
                                else -> "+1\r+2\r+3\r+4\r-1\r+5\r"
                            }
                            return MockResponse().setResponseCode(200).setBody(content.substring(startIndex ?: 0))
                        }
                        "/v1/list_2" -> {
                            val range = request.headers["range"]
                            val startIndex = range!!.substring(6, range!!.length-1).toIntOrNull()
                            download_list_2_count++

                            var content: String = when (download_list_2_count) {
                                1 -> "+a\r+b\r"
                                2 -> "+a\r+b\r+c\r+d\r-a\r-b\r"
                                3 -> "+c\r+d\r" // new file, ids are consolidated
                                else -> "+c\r+d\r-a\r"
                            }
                            return MockResponse().setResponseCode(200).setBody(content.substring(startIndex ?: 0))
                        }
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        }

        options = StatsigOptions().apply {
            api = server.url("/v1").toString()
        }

        statsigUser = StatsigUser("123").apply {
            email = "testuser@statsig.com"
        }

        randomUser = StatsigUser("random")
        driver = StatsigServer.create("secret-testcase", options)
    }

    @Test
    fun testFeatureGate() = runBlocking {
        driver.initialize()
        val now = System.currentTimeMillis()
        assert(driver.checkGate(statsigUser, "always_on_gate"))
        assert(driver.checkGate(statsigUser, "on_for_statsig_email"))
        assert(!driver.checkGate(randomUser, "on_for_statsig_email"))
        driver.shutdown()

        val eventLogInput = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }

        assert(eventLogInput.events.size == 3)
        assert(eventLogInput.events[0].eventName == "statsig::gate_exposure")
        assert(eventLogInput.events[0].eventMetadata!!["gate"].equals("always_on_gate"))
        assert(eventLogInput.events[0].eventMetadata!!["gateValue"].equals("true"))
        assert(eventLogInput.events[0].eventMetadata!!["ruleID"].equals("6N6Z8ODekNYZ7F8gFdoLP5"))
        assert(eventLogInput.events[0].time!!/1000 == now/1000)

        assert(eventLogInput.events[1].eventName == "statsig::gate_exposure")
        assert(eventLogInput.events[1].eventMetadata!!["gate"].equals("on_for_statsig_email"))
        assert(eventLogInput.events[1].eventMetadata!!["gateValue"].equals("true"))
        assert(eventLogInput.events[1].eventMetadata!!["ruleID"].equals("7w9rbTSffLT89pxqpyhuqK"))
        assert(eventLogInput.events[1].time!!/1000 == now/1000)

        assert(eventLogInput.events[2].eventName == "statsig::gate_exposure")
        assert(eventLogInput.events[2].eventMetadata!!["gate"].equals("on_for_statsig_email"))
        assert(eventLogInput.events[2].eventMetadata!!["gateValue"].equals("false"))
        assert(eventLogInput.events[2].eventMetadata!!["ruleID"].equals("default"))
        assert(eventLogInput.events[2].time!!/1000 == now/1000)
    }

    @Test
    fun testDynamicConfig() = runBlocking {
        driver.initialize()
        val now = System.currentTimeMillis()
        var config = driver.getConfig(statsigUser, "test_config")
        assert(config.getInt("number", 0) == 7)
        assert(config.getString("string", "") == "statsig")
        assert(!config.getBoolean("boolean", true))
        config = driver.getConfig(randomUser, "test_config")
        assert(config.getInt("number", 0) == 4)
        assert(config.getString("string", "") == "default")
        assert(config.getBoolean("boolean", true))

        var groups = driver._getExperimentGroups("test_config")
        assert(groups["statsig email"]!!.get("value")!!.equals("{number=7.0, string=statsig, boolean=false}"))

        driver.shutdown()

        val eventLogInput = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }
        assert(eventLogInput.events.size == 2)
        assert(eventLogInput.events[0].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[0].eventMetadata!!["config"].equals("test_config"))
        assert(eventLogInput.events[0].eventMetadata!!["ruleID"].equals("1kNmlB23wylPFZi1M0Divl"))
        assert(eventLogInput.events[0].time!!/1000 == now/1000)

        assert(eventLogInput.events[1].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[1].eventMetadata!!["config"].equals("test_config"))
        assert(eventLogInput.events[1].eventMetadata!!["ruleID"].equals("default"))
        assert(eventLogInput.events[1].time!!/1000 == now/1000)
    }

    @Test
    fun testExperiment() = runBlocking {
        driver.initialize()
        val now = System.currentTimeMillis()
        var config = driver.getExperiment(statsigUser, "sample_experiment")
        assert(config.getString("experiment_param", "") == "test")
        config = driver.getExperiment(randomUser, "sample_experiment")
        assert(config.getString("experiment_param", "") == "control")
        // check below should not result in an exposure log
        driver.getExperimentWithExposureLoggingDisabled(randomUser, "sample_experiment_2")
        driver.shutdown()

        val eventLogInput = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }
        assert(eventLogInput.events.size == 2)
        assert(eventLogInput.events[0].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[0].eventMetadata!!["config"].equals("sample_experiment"))
        assert(eventLogInput.events[0].eventMetadata!!["ruleID"].equals("2RamGujUou6h2bVNQWhtNZ"))
        assert(eventLogInput.events[0].time!!/1000 == now/1000)

        assert(eventLogInput.events[1].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[1].eventMetadata!!["config"].equals("sample_experiment"))
        assert(eventLogInput.events[1].eventMetadata!!["ruleID"].equals("2RamGsERWbWMIMnSfOlQuX"))
        assert(eventLogInput.events[1].time!!/1000 == now/1000)
    }

    @Test
    fun testLayer() = runBlocking {
        driver.initialize()
        val now = System.currentTimeMillis()
        var config = driver.getLayer(statsigUser, "a_layer")
        assertEquals("test", config.getString("experiment_param", ""))
        assertTrue(config.getBoolean("layer_param", false))
        assertTrue(config.getBoolean("second_layer_param", false))

        config = driver.getLayer(statsigUser, "b_layer_no_alloc")
        assertEquals("foo", config.getString("a_param", ""))

        driver.shutdown()

        val eventLogInput = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }

        val events = eventLogInput.events
        assertEquals(2, events.size)

        assertEquals("statsig::layer_exposure", events[0].eventName)
        assertEquals("a_layer", events[0].eventMetadata!!["config"])
        assertEquals("2RamGujUou6h2bVNQWhtNZ", events[0].eventMetadata!!["ruleID"])
        assertEquals("sample_experiment", events[0].eventMetadata!!["allocatedExperiment"])
        assertEquals(now/1000, eventLogInput.events[0].time!!/1000)

        assertEquals("statsig::layer_exposure", events[1].eventName)
        assertEquals("b_layer_no_alloc", events[1].eventMetadata!!["config"])
        assertEquals("layer_defaults", events[1].eventMetadata!!["ruleID"])
        assertEquals(now/1000, eventLogInput.events[1].time!!/1000)
    }

    @Test
    fun testLogEvent() = runBlocking {
        driver.initialize()
        val now = System.currentTimeMillis()
        driver.logEvent(statsigUser, "purchase", 2.99, mapOf("item_name" to "remove_ads"))
        driver.shutdown()

        val eventLogInput = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }

        assert(eventLogInput.events.size == 1)
        assert(eventLogInput.events[0].eventName == "purchase")
        assert(eventLogInput.events[0].eventValue == 2.99)
        assert(eventLogInput.events[0].eventMetadata!!["item_name"].equals("remove_ads"))
        assert(eventLogInput.events[0].time!!/1000 == now/1000)
    }

    @Test
    fun testBackgroundSync() = runBlocking {
        download_config_count = 0
        download_id_list_count = 0
        download_list_1_count = 0
        download_list_2_count = 0

        CONFIG_SYNC_INTERVAL_MS = 1000
        ID_LISTS_SYNC_INTERVAL_MS = 1000

        driver.initialize()
        val privateEvaluatorField = driver.javaClass.getDeclaredField("configEvaluator")
        privateEvaluatorField.isAccessible = true

        val evaluator = privateEvaluatorField[driver] as Evaluator

        assert(download_config_count == 1)
        assert(download_id_list_count == 1)

        assert(evaluator.idLists["list_1"]?.ids == mutableSetOf("1", "2"))
        assert(evaluator.idLists["list_2"]?.ids == mutableSetOf("a", "b"))
        assert(evaluator.idLists["list_1"]?.creationTime == 1L)
        assert(evaluator.idLists["list_2"]?.creationTime == 1L)

        Thread.sleep(1100)
        assert(download_config_count == 2)
        assert(download_id_list_count == 2)
        assert(evaluator.idLists["list_1"]?.ids == mutableSetOf("2", "3", "4"))
        assert(evaluator.idLists["list_2"]?.ids == mutableSetOf("c", "d"))
        assert(evaluator.idLists["list_1"]?.creationTime == 1L)
        assert(evaluator.idLists["list_2"]?.creationTime == 1L)

        Thread.sleep(1100)
        assert(download_config_count == 3)
        assert(download_id_list_count == 3)
        assert(evaluator.idLists["list_1"] == null)
        assert(evaluator.idLists["list_2"]?.ids == mutableSetOf("c", "d"))
        assert(evaluator.idLists["list_2"]?.creationTime == 2L)

        Thread.sleep(1100)
        assert(download_config_count == 4)
        assert(download_id_list_count == 4)
        assert(evaluator.idLists["list_1"]?.ids == mutableSetOf("2", "3", "4", "5"))
        assert(evaluator.idLists["list_2"]?.ids == mutableSetOf("c", "d"))
        assert(evaluator.idLists["list_1"]?.creationTime == 1L)
        assert(evaluator.idLists["list_2"]?.creationTime == 2L)

        driver.shutdown()

        Thread.sleep(2000)
        assert(download_config_count == 4)
        assert(download_id_list_count == 4)
    }
}
