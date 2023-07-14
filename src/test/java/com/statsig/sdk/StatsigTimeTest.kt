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
import org.junit.After
import org.junit.Before
import org.junit.Test

class StatsigTimeTest {

    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions

    private lateinit var driver: StatsigServer
    private lateinit var downloadConfigSpecsResponse: String

    @Before
    fun setup() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        eventLogInputCompletable = CompletableDeferred()
        downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs_time.json")?.readText() ?: ""

        server = MockWebServer()
        server.start(8899)
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
                        "/v1/get_id_lists" -> {
                            val list = mapOf(
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
                            return MockResponse().setResponseCode(200).setBody(gson.toJson(list))
                        }
                        "/v1/list_1" -> {
                            val range = request.headers["range"]
                            val startIndex = range!!.substring(6, range.length - 1).toIntOrNull()

                            var content = "+1\r+2\r"
                            return MockResponse().setResponseCode(200).setBody(content.substring(startIndex ?: 0))
                        }
                        "/v1/list_2" -> {
                            val range = request.headers["range"]
                            val startIndex = range!!.substring(6, range.length - 1).toIntOrNull()

                            var content = "+a\r+b\r"
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

        driver = StatsigServer.create("secret-testcase", options)
    }

    @After
    fun afterEach() {
        server.shutdown()
    }

    @Test
    fun testAfter() = runBlocking {
        driver.initialize()
        val isoUser = StatsigUser("123").apply {
            email = "testuser@statsig.com"
            custom = mapOf(
                "iso" to "2023-06-03T07:20:46.109Z"
            )
        }

        val secondsUser = StatsigUser("random").apply {
            custom = mapOf("iso" to "1685776846")
        }

        val msUser = StatsigUser("random").apply {
            custom = mapOf("iso" to "1685776846109")
        }
        assert(driver.checkGate(isoUser, "test_iso_timestamp"))
        assert(driver.checkGate(secondsUser, "test_iso_timestamp"))
        assert(driver.checkGate(msUser, "test_iso_timestamp"))
    }

    @Test
    fun testBefore() = runBlocking {
        driver.initialize()
        val isoUser = StatsigUser("123").apply {
            email = "testuser@statsig.com"
            custom = mapOf(
                "iso" to "2023-05-03T07:20:46.109Z"
            )
        }

        val secondsUser = StatsigUser("random").apply {
            custom = mapOf("iso" to "1675776846")
        }

        val msUser = StatsigUser("random").apply {
            custom = mapOf("iso" to "1675776846109")
        }
        assert(!driver.checkGate(isoUser, "test_iso_timestamp"))
        assert(!driver.checkGate(secondsUser, "test_iso_timestamp"))
        assert(!driver.checkGate(msUser, "test_iso_timestamp"))
    }
}
