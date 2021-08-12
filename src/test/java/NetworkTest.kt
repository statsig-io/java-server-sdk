import com.statsig.sdk.StatsigEvent
import com.statsig.sdk.StatsigNetwork
import com.statsig.sdk.StatsigOptions
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

class NetworkTest {

    private val mainThreadSurrogate = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThreadSurrogate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun testRetry() = runBlockingTest {
        val server = MockWebServer()
        server.start()

        val errResponse = MockResponse()
        errResponse.setResponseCode(500)
        errResponse.setBody("{}")
        server.enqueue(errResponse)

        val successResponse = MockResponse()
        successResponse.setResponseCode(200)
        successResponse.setBody("{}")
        server.enqueue(successResponse)

        val metadata: MutableMap<String, String> = HashMap()
        metadata["sdkType"] = "test"
        val options = StatsigOptions()
        options.api = server.url("/v1").toString()

        val net = spyk(StatsigNetwork("secret-123", options, metadata, 1))

        async {
            net.postLogs(listOf(StatsigEvent("TestEvent")), metadata)
            val request = server.takeRequest()
            assertEquals("POST /v1/log_event HTTP/1.1", request.requestLine)
            server.takeRequest()
        }.await()

        coVerifySequence {
            net.postLogs(any(), any())
            net.retryPostLogs(any(), any(), any(), any()) // 500
            net.retryPostLogs(any(), any(), any(), any()) // 200
        }
    }
}
