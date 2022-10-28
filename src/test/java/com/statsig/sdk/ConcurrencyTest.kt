
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.statsig.sdk.LogEventInput
import com.statsig.sdk.StatsigE2ETest
import com.statsig.sdk.StatsigOptions
import com.statsig.sdk.StatsigServer
import com.statsig.sdk.StatsigUser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.Thread.sleep
import kotlin.concurrent.thread

private const val TEST_TIMEOUT = 10L

class ConcurrencyTest {
    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions

    private lateinit var user: StatsigUser
    private lateinit var driver: StatsigServer

    private var flushedEventCount = 0
    private var getIDListCount = 0
    private var downloadList1Count = 0

    @Before
    fun setup() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        eventLogInputCompletable = CompletableDeferred()
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
        user = StatsigUser("dan")
        server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    when (request.path) {
                        "/v1/download_config_specs" -> {
                            return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                        }
                        "/v1/log_event" -> {
                            val logBody = request.body.readUtf8()
                            val input = gson.fromJson(logBody, LogEventInput::class.java)
                            flushedEventCount += input.events.size
                            return MockResponse().setResponseCode(200).setBody("")
                        }
                        "/v1/get_id_lists" -> {
                            getIDListCount++
                            return MockResponse().setResponseCode(200).setBody(
                                Gson().toJson(
                                    mapOf(
                                        "list_1" to
                                            mapOf(
                                                "name" to "list_1",
                                                "size" to 3 * getIDListCount,
                                                "url" to server.url("/list_1").toString(),
                                                "creationTime" to 1,
                                                "fileID" to "file_id_1"
                                            )
                                    )
                                )
                            )
                        }
                        "/list_1" -> {
                            downloadList1Count++
                            var body = "+7/rrkvF6\n"
                            if (downloadList1Count > 1) {
                                body = "+$downloadList1Count\n-$downloadList1Count\n"
                            }
                            return MockResponse().setResponseCode(200).setBody(body)
                        }
                    }

                    return MockResponse().setResponseCode(404)
                }
            }
        }

        val options = StatsigOptions().apply {
            api = server.url("/v1").toString()

            // set sync interval to be short, so we are modifying and checking values at the same time
            rulesetsSyncIntervalMs = 10
            idListsSyncIntervalMs = 10
        }
        driver = StatsigServer.create("secret-testcase", options)
    }

    @Test
    fun testCallingAPIsFromDifferentThreads() = runBlocking {
        driver.initialize()
        val threads = arrayListOf<Thread>()
        for (i in 1..20) {
            val t =
                thread {
                    for (j in 1..20) {
                        val user = StatsigUser("user_id_$i")
                        user.email = "testuser@statsig.com"

                        runBlocking {
                            driver.logEvent(user, "test_event", 1.0, mapOf("key" to "value"))
                            assertTrue(driver.checkGate(user, "always_on_gate"))
                            assertTrue(driver.checkGate(user, "on_for_statsig_email"))
                            // regular_user_id is in the id list
                            assertTrue(driver.checkGate(StatsigUser("regular_user_id"), "on_for_id_list"))
                            driver.logEvent(user, "test_event_2")
                            val expParam = driver.getExperiment(user, "sample_experiment").getString("experiment_param", "default")
                            assertTrue(expParam == "test" || expParam == "control")
                            driver.logEvent(user, "test_event_3")

                            val config = driver.getConfig(user, "test_config")
                            assertEquals(7, config.getInt("number", 0))

                            val layer = driver.getLayer(user, "a_layer")
                            assertTrue(layer.getBoolean("layer_param", false))
                        }
                        sleep(10)
                    }
                }
            threads.add(t)
        }

        for (t in threads) {
            t.join()
        }

        driver.shutdown()
        assertEquals(3600, flushedEventCount)
    }
}
