package com.statsig.sdk

import com.statsig.sdk.network.StatsigTransport
import io.mockk.*
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
        private var sdkExceptionWaiter = CountDownLatch(1)

        @BeforeClass
        @JvmStatic
        internal fun beforeAll() {
            mockkConstructor(StatsigTransport::class)
            every { anyConstructed<StatsigTransport>().shutdown() } throws Exception("Test Network Shutdown")

            mockkConstructor(ConfigEvaluation::class)

            mockkConstructor(StatsigLogger::class)

            mockkConstructor(SpecStore::class)
            mockkConstructor(SpecUpdater::class)
            every { anyConstructed<SpecStore>().getLayerConfig(any()) } throws Exception("Test Evaluator LayerConfig")
            every { anyConstructed<SpecStore>().getLayer(any()) } throws Exception("Test Evaluator Layers")
            every { anyConstructed<StatsigLogger>().log(match { it.eventName != "statsig::diagnostics" }) } throws Exception("Test Logger Log")
            coEvery { anyConstructed<SpecUpdater>().getConfigSpecsFromNetwork() } coAnswers {
                if (throwOnDownloadConfigSpecs) {
                    throw Exception("Bad Config Specs")
                }
                callOriginal()
            }

            server = MockWebServer()
            server.apply {
                dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/v1/sdk_exception") {
                            sdkExceptionWaiter.countDown()
                            requests.add(request)
                        }
                        onRequestWaiter.countDown()
                        return MockResponse().setResponseCode(200).setBody("{}")
                    }
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun afterAll() {
            unmockkAll()
            sdkExceptionWaiter = CountDownLatch(1)
            server.shutdown()
        }
    }

    private fun getStatsigInstance(shouldInitialize: Boolean = true) = runBlocking {
        val statsig = spyk(StatsigServer.create())
        every {
            statsig.setup(any(), any())
        } answers {
            callOriginal()
            statsig.errorBoundary.uri = server.url("/v1/sdk_exception").toUri()
        }

        if (shouldInitialize) {
            runBlocking {
                statsig.initialize("secret-key", StatsigOptions(disableDiagnostics = true, api = server.url("/v1").toString()))
            }
        }

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
            statsig.initialize("secret-key", StatsigOptions(disableDiagnostics = true))
        }

        assert(sdkExceptionWaiter.await(1, TimeUnit.SECONDS))
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
        assert(sdkExceptionWaiter.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun testErrorsWithGetExperiment() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.getExperiment(user, "an_experiment")
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
        assert(sdkExceptionWaiter.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun testErrorsWithLogStringEvent() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.logEvent(user, "an_event", "a_value")
        onRequestWaiter.await(1, TimeUnit.SECONDS)
        assert(sdkExceptionWaiter.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun testErrorsWithLogDoubleEvent() = runBlocking {
        val statsig = getStatsigInstance()
        statsig.logEvent(user, "an_event", 1.2)
        onRequestWaiter.await(1, TimeUnit.SECONDS)
        assert(sdkExceptionWaiter.await(1, TimeUnit.SECONDS))
    }
}
