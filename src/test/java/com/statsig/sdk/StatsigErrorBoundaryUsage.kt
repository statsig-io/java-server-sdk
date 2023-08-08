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
        private lateinit var onRequestWaiter: CountDownLatch
        private val requests = arrayListOf<RecordedRequest>()
        private var throwOnDownloadConfigSpecs = false

        @BeforeClass
        @JvmStatic
        internal fun beforeAll() {
            mockkConstructor(StatsigNetwork::class)
            every { anyConstructed<StatsigNetwork>().shutdown() } throws Exception("Test Network Shutdown")

            mockkConstructor(ConfigEvaluation::class)
            every { anyConstructed<ConfigEvaluation>().fetchFromServer } throws Exception("Test Config Eval")

            mockkConstructor(StatsigLogger::class)

            mockkConstructor(SpecStore::class)
            every { anyConstructed<SpecStore>().getLayerConfig(any()) } throws Exception("Test Evaluator LayerConfig")
            every { anyConstructed<SpecStore>().getLayer(any()) } throws Exception("Test Evaluator Layers")
            every { anyConstructed<StatsigLogger>().log(match { it.eventName != "statsig::diagnostics" }) } throws Exception("Test Logger Log")
            coEvery { anyConstructed<SpecStore>().downloadConfigSpecs() } coAnswers {
                if (throwOnDownloadConfigSpecs) {
                    throw Exception("Bad Config Specs")
                }
                callOriginal()
            }

            server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.add(request)
                        onRequestWaiter.countDown()
                        return MockResponse().setResponseCode(202)
                    }
                }
        }

        @AfterClass
        @JvmStatic
        fun afterAll() {
            unmockkAll()
            server.shutdown()
        }
    }

    private fun getStatsigInstance(shouldInitialize: Boolean = true) = runBlocking {
        val statsig = StatsigServer.create("secret-key", StatsigOptions(api = "http://localhost", disableDiagnostics = true))
        if (shouldInitialize) {
            runBlocking {
                statsig.initialize()
            }
        }

        statsig.errorBoundary.uri = server.url("/v1/sdk_exception").toUri()
        return@runBlocking statsig
    }

    @Before
    internal fun beforeEach() {
        throwOnDownloadConfigSpecs = false
        onRequestWaiter = CountDownLatch(1)
        requests.clear()
    }

    @Test
    fun testErrorsWithInitialize() = runBlocking {
        val statsig = getStatsigInstance(shouldInitialize = false)
        throwOnDownloadConfigSpecs = true

        runBlocking {
            statsig.initialize()
        }

        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithShutdown() = runBlocking {
        val statsig = getStatsigInstance()
        assertEquals(0, requests.size)

        statsig.shutdown()
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithCheckGate() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.checkGate(user, "a_gate")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetConfig() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.getConfig(user, "a_config")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetExperiment() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.getExperiment(user, "an_experiment")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetExperimentWithExposureLoggingDisabled() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.getExperimentWithExposureLoggingDisabled(user, "an_experiment")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetExperimentInLayerForUser() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.getExperimentInLayerForUser(user, "a_layer")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithGetLayer() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.getLayer(user, "a_layer")
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithLogStringEvent() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.logEvent(user, "an_event", "a_value")
        onRequestWaiter.await(1, TimeUnit.SECONDS)
        assertEquals(1, requests.size)
    }

    @Test
    fun testErrorsWithLogDoubleEvent() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.logEvent(user, "an_event", 1.2)
        onRequestWaiter.await(1, TimeUnit.SECONDS)
        assertEquals(1, requests.size)
    }
}
