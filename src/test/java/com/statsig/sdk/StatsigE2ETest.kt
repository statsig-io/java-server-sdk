package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.annotations.SerializedName
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

internal data class LogEventInput(
    @SerializedName("events") val events: Array<StatsigEvent>,
)

private const val TEST_TIMEOUT = 100L
private const val SYNC_INTERVAL = 1000L

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
    private lateinit var downloadConfigSpecsResponse: String

    private var download_config_count: Int = 0
    private var download_id_list_count: Int = 0
    private var download_list_1_count: Int = 0
    private var download_list_2_count: Int = 0
    private var bootstrap_callback_count: Int = 0

    @Before
    fun setup() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        eventLogInputCompletable = CompletableDeferred()
        downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""

        server = MockWebServer()
        server.start(8899)
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
                            val startIndex = range!!.substring(6, range.length - 1).toIntOrNull()
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
                            val startIndex = range!!.substring(6, range.length - 1).toIntOrNull()
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

    @After
    fun afterEach() {
        server.shutdown()
    }

    @Test
    fun testFeatureGate() = runBlocking {
        featureGateHelper()
    }

    @Test
    fun testFeatureGateWithBootstrap() = runBlocking {
        bootstrap()
        featureGateHelper()
    }

    private fun featureGateHelper() = runBlocking {
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
        assert(eventLogInput.events[0].time!! / 1000 == now / 1000)

        assert(eventLogInput.events[1].eventName == "statsig::gate_exposure")
        assert(eventLogInput.events[1].eventMetadata!!["gate"].equals("on_for_statsig_email"))
        assert(eventLogInput.events[1].eventMetadata!!["gateValue"].equals("true"))
        assert(eventLogInput.events[1].eventMetadata!!["ruleID"].equals("7w9rbTSffLT89pxqpyhuqK"))
        assert(eventLogInput.events[1].time!! / 1000 == now / 1000)

        assert(eventLogInput.events[2].eventName == "statsig::gate_exposure")
        assert(eventLogInput.events[2].eventMetadata!!["gate"].equals("on_for_statsig_email"))
        assert(eventLogInput.events[2].eventMetadata!!["gateValue"].equals("false"))
        assert(eventLogInput.events[2].eventMetadata!!["ruleID"].equals("default"))
        assert(eventLogInput.events[2].time!! / 1000 == now / 1000)
    }

    @Test
    fun testDynamicConfig() = runBlocking {
        dynamicConfigHelper()
    }

    @Test
    fun testDynamicConfigWithBootstrap() = runBlocking {
        bootstrap()
        dynamicConfigHelper()
    }

    private fun dynamicConfigHelper() = runBlocking {
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
        assert(groups["statsig email"]!!.get("value")!!.equals("{number=7, string=statsig, boolean=false}"))

        driver.shutdown()

        val eventLogInput = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }
        assert(eventLogInput.events.size == 2)
        assert(eventLogInput.events[0].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[0].eventMetadata!!["config"].equals("test_config"))
        assert(eventLogInput.events[0].eventMetadata!!["ruleID"].equals("1kNmlB23wylPFZi1M0Divl"))
        assert(eventLogInput.events[0].time!! / 1000 == now / 1000)

        assert(eventLogInput.events[1].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[1].eventMetadata!!["config"].equals("test_config"))
        assert(eventLogInput.events[1].eventMetadata!!["ruleID"].equals("default"))
        assert(eventLogInput.events[1].time!! / 1000 == now / 1000)
    }

    @Test
    fun testExperiment() = runBlocking {
        experimentHelper()
    }

    @Test
    fun testExperimentWithBootstrap() = runBlocking {
        bootstrap()
        experimentHelper()
    }

    private fun experimentHelper() = runBlocking {
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
        assert(eventLogInput.events[0].time!! / 1000 == now / 1000)

        assert(eventLogInput.events[1].eventName == "statsig::config_exposure")
        assert(eventLogInput.events[1].eventMetadata!!["config"].equals("sample_experiment"))
        assert(eventLogInput.events[1].eventMetadata!!["ruleID"].equals("2RamGsERWbWMIMnSfOlQuX"))
        assert(eventLogInput.events[1].time!! / 1000 == now / 1000)
    }

    @Test
    fun testLogEvent() = runBlocking {
        logEventHelper()
    }

    @Test
    fun testLogEventWithBootstrap() = runBlocking {
        bootstrap()
        logEventHelper()
    }

    private fun logEventHelper() = runBlocking {
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
        assert(eventLogInput.events[0].time!! / 1000 == now / 1000)
    }
    @Test
    fun testBackgroundSync() = runBlocking {
        download_config_count = 0
        options = StatsigOptions().apply {
            api = server.url("/v1").toString()
            rulesetsSyncIntervalMs = SYNC_INTERVAL
            idListsSyncIntervalMs = SYNC_INTERVAL
        }

        driver = StatsigServer.create("secret-testcase", options)
        backgroundSyncHelper()
    }

    private fun bootstrap(withFastSync: Boolean = false) = runBlocking {
        if (withFastSync) {
            options = StatsigOptions(
                bootstrapValues = downloadConfigSpecsResponse,
                rulesUpdatedCallback = {
                    bootstrap_callback_count++
                }
            ).apply {
                api = server.url("/v1").toString()
                rulesetsSyncIntervalMs = SYNC_INTERVAL
                idListsSyncIntervalMs = SYNC_INTERVAL
            }
        } else {
            options = StatsigOptions(
                bootstrapValues = downloadConfigSpecsResponse,
                rulesUpdatedCallback = {
                    bootstrap_callback_count++
                    val specs = gson.fromJson(it, APIDownloadedConfigs::class.java)
                    assert(gson.toJson(specs) == gson.toJson(gson.fromJson(downloadConfigSpecsResponse, APIDownloadedConfigs::class.java)))
                }
            ).apply {
                api = server.url("/v1").toString()
            }
        }

        driver = StatsigServer.create("secret-testcase", options)
    }

    @Test
    fun testBackgroundSyncWithBootstrap() = runBlocking {
        bootstrap(true)
        // the initialize is synchronous, and wont trigger a call to download_config_specs
        // this normalizes the count so the remainder of the test works
        download_config_count = 1
        bootstrap_callback_count = 0
        backgroundSyncHelper(true)

        // callback will fire 1 less time than the total download count
        // because the initial bootstrap counts as a download config count
        assert(bootstrap_callback_count == download_config_count - 1)
    }

    private fun waitFor(expected: Any?, action: () -> Any?) = runBlocking {
        var i = 0
        var value = action()
        while (i < 1000 && value != expected) {
            Thread.sleep(10)
            i++
            value = action()
        }

        if (value != expected) {
            throw Exception("Value never matched, expected: $expected, actual: $value")
        }
    }

    private fun backgroundSyncHelper(withBootstrap: Boolean = false) = runBlocking {
        download_id_list_count = 0
        download_list_1_count = 0
        download_list_2_count = 0

        driver.initialize()

        val specStore = TestUtil.getSpecStoreFromStatsigServer(driver)

        assertEquals(1, download_config_count)
        assertEquals(1, download_id_list_count)

        assertEquals(mutableSetOf("1", "2"), specStore.getIDList("list_1")?.ids)
        assertEquals(mutableSetOf("a", "b"), specStore.getIDList("list_2")?.ids)
        assertEquals(1L, specStore.getIDList("list_1")?.creationTime)
        assertEquals(1L, specStore.getIDList("list_2")?.creationTime)

        waitFor(mutableSetOf("2", "3", "4")) { specStore.getIDList("list_1")?.ids }
        waitFor(mutableSetOf("c", "d")) { specStore.getIDList("list_2")?.ids }
        assertEquals(2, download_config_count)
        assertEquals(2, download_id_list_count)
        assertEquals(1L, specStore.getIDList("list_1")?.creationTime)
        assertEquals(1L, specStore.getIDList("list_2")?.creationTime)

        waitFor(2L) { specStore.getIDList("list_2")?.creationTime }
        waitFor(3) { download_config_count }

        assertEquals(3, download_config_count)
        assertEquals(3, download_id_list_count)
        assertEquals(mutableSetOf("c", "d"), specStore.getIDList("list_2")?.ids)
        assertEquals(2L, specStore.getIDList("list_2")?.creationTime)

        waitFor(4) { download_config_count }
        assertEquals(4, download_config_count)
        assertEquals(4, download_id_list_count)
        assertEquals(mutableSetOf("2", "3", "4", "5"), specStore.getIDList("list_1")?.ids)
        assertEquals(mutableSetOf("c", "d"), specStore.getIDList("list_2")?.ids)
        assertEquals(1L, specStore.getIDList("list_1")?.creationTime)
        assertEquals(2L, specStore.getIDList("list_2")?.creationTime)

        if (withBootstrap) {
            waitFor(3) { bootstrap_callback_count }
        }

        driver.shutdown()

        assertEquals(4, download_config_count)
        assertEquals(4, download_id_list_count)
    }
}
