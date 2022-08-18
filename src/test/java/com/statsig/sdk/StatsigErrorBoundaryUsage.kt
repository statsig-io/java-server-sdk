package com.statsig.sdk

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StatsigErrorBoundaryUsage {
    private val user = StatsigUser("dan")

    companion object {
        private lateinit var server: MockWebServer
        private val exception = Exception("Test Exception")
        private lateinit var onRequestWaiter: CountDownLatch
        private lateinit var statsig: StatsigServer
        private val requests = arrayListOf<RecordedRequest>()
        private var throwOnDownloadConfigSpecs = false

        @BeforeClass
        @JvmStatic
        internal fun beforeAll() {
            mockkConstructor(Evaluator::class)
            every { anyConstructed<Evaluator>().layers } throws exception

            mockkConstructor(StatsigNetwork::class)
            every { anyConstructed<StatsigNetwork>().shutdown() } throws exception

            mockkConstructor(ConfigEvaluation::class)
            every { anyConstructed<ConfigEvaluation>().fetchFromServer } throws exception

            mockkConstructor(StatsigLogger::class)
            coEvery { anyConstructed<StatsigLogger>().log(any()) } throws exception

            coEvery { anyConstructed<StatsigNetwork>().downloadConfigSpecs() } coAnswers {
                if (throwOnDownloadConfigSpecs) {
                    throw exception
                }
                callOriginal()
            }
        }

        @AfterClass
        @JvmStatic
        fun afterAll() {
            unmockkAll()
        }
    }

    @After
    internal fun after() {
        server.shutdown()
    }

    @Before
    internal fun beforeEach() {
        throwOnDownloadConfigSpecs = false

        statsig = StatsigServer.create("secret-key", StatsigOptions(api = "http://localhost"))
        runBlocking {
            statsig.initialize()
        }

        server = MockWebServer()
        statsig.errorBoundary.uri = server.url("/v1/sdk_exception").toUri()

        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    onRequestWaiter.countDown()
                    return MockResponse().setResponseCode(202)
                }
            }


        onRequestWaiter = CountDownLatch(1)
        requests.clear()
        statsig.errorBoundary.seen.clear()
    }

    @Test
    fun testErrorsWithInitialize() = runBlocking {
        val localStatsig = StatsigServer.create("secret-key", StatsigOptions(api = "http://localhost"))
        localStatsig.errorBoundary.uri = server.url("/v1/sdk_exception").toUri()
        throwOnDownloadConfigSpecs = true

        localStatsig.initialize()
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithShutdown() = runBlocking {
        val localStatsig = StatsigServer.create("secret-key", StatsigOptions(api = "http://localhost"))
        localStatsig.errorBoundary.uri = server.url("/v1/sdk_exception").toUri()
        localStatsig.initialize()
        assertEquals(0, requests.size)

        localStatsig.shutdown()
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithCheckGate() = runBlocking {
        statsig.checkGate(user, "a_gate")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetConfig() = runBlocking {
        statsig.getConfig(user, "a_config")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetExperiment() = runBlocking {
        statsig.getExperiment(user, "an_experiment")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetExperimentWithExposureLoggingDisabled() = runBlocking {
        statsig.getExperimentWithExposureLoggingDisabled(user, "an_experiment")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetExperimentInLayerForUser() = runBlocking {
        statsig.getExperimentInLayerForUser(user, "a_layer")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetLayer() = runBlocking {
        statsig.getLayer(user, "a_layer")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetLayerWithCustomExposureLogging() = runBlocking {
        statsig.getLayerWithCustomExposureLogging(user, "a_layer") {}
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithLogStringEvent() = runBlocking {
        statsig.logEvent(user, "an_event", "a_value")
        onRequestWaiter.await(1, TimeUnit.SECONDS)
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithLogDoubleEvent() = runBlocking {
        statsig.logEvent(user, "an_event", 1.2)
        onRequestWaiter.await(1, TimeUnit.SECONDS)
        assertEquals(1, requests.size)
    }
}