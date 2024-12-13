package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private const val TEST_TIMEOUT = 10L
private const val ITERATIONS = 10001

class SamplingTest {
    private lateinit var gson: Gson
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    private lateinit var options: StatsigOptions

    private lateinit var user: StatsigUser
    private lateinit var statsigServer: StatsigServer

    @Before
    fun setup() {
        gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        eventLogInputCompletable = CompletableDeferred()
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs_sampling.json")?.readText() ?: ""
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
        statsigServer = StatsigServer.create()
    }

    @Test
    fun testGateThatShouldBeSampled() = runBlocking {
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser(generateRandomUserId())
            statsigServer.checkGateSync(testUser, "sampled_metric_lifts_disabled")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertTrue(events.size in 1..5)
        for (event in events.drop(1)) {
            Assert.assertNotNull(event.statsigMetadata?.samplingRate)
            Assert.assertNotNull(event.statsigMetadata?.samplingStatus)
            Assert.assertNotNull(event.statsigMetadata?.samplingStatus)
        }
    }

    @Test
    fun testGateShouldNotBeSampled() = runBlocking {
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser("user_$i")
            statsigServer.checkGateSync(testUser, "analytical_gate")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertEquals(events.size, 101)
        for (event in events) {
            Assert.assertNull(event.statsigMetadata?.samplingRate)
            Assert.assertNull(event.statsigMetadata?.samplingStatus)
            Assert.assertNull(event.statsigMetadata?.samplingStatus)
        }
    }

    @Test
    fun testGateShouldBeSampledButForwardAllExposure() = runBlocking {
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser("user_$i")
            statsigServer.checkGateSync(testUser, "sampled_default_gate")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertEquals(events.size, 101)
    }

    @Test
    fun testConfigShouldBeSampled() = runBlocking {
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser(generateRandomUserId())
            statsigServer.getConfigSync(testUser, "sampled_disabled_config")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertTrue(events.size in 1..5)
        for (event in events.drop(1)) {
            Assert.assertNotNull(event.statsigMetadata?.samplingRate)
            Assert.assertNotNull(event.statsigMetadata?.samplingStatus)
            Assert.assertNotNull(event.statsigMetadata?.samplingStatus)
        }
    }

    @Test
    // because the rule id endwith override
    fun testGateShouldNotBeSampledWithRuleIdOverride() = runBlocking {
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser("user-$i") // Note: this is the target user for this gate
            statsigServer.checkGateSync(testUser, "analytical_overriden_gate")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertEquals(events.size, 101)
    }

    @Test
    fun testNonProductionWithShouldSampleGate() = runBlocking {
        options.setTier("staging")
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser("user_$i")
            statsigServer.checkGateSync(testUser, "analytical_gate")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertEquals(events.size, 101)
    }

    @Test
    fun testConfigShouldNotBeSampled() = runBlocking {
        statsigServer.initialize("secret-key", options)
        for (i in 1..101) {
            val testUser = StatsigUser("user_$i")
            statsigServer.getExperimentSync(testUser, "analytical_exp")
        }
        statsigServer.shutdown()
        val events = captureEvents()

        Assert.assertEquals(events.size, 101)
    }

    @Test
    // for LARGE number
    fun testGateSamplingRate() = runBlocking {
        statsigServer.initialize("secret-key", options)

        for (i in 1..ITERATIONS) {
            val testUser = StatsigUser(generateRandomUserId())
            statsigServer.checkGateSync(testUser, "sampled_metric_lifts_disabled")
        }

        statsigServer.shutdown()
        val events = captureEvents()

        val approximateSamplingRate = events.size.toDouble() / ITERATIONS
        println(" Sampling Rate: $approximateSamplingRate")
        println(events.size)

        Assert.assertTrue(events.size in 90..110) // Tolerance range
    }

    private fun captureEvents(): Array<StatsigEvent> = runBlocking {
        val logs = withTimeout(TEST_TIMEOUT) {
            eventLogInputCompletable.await()
        }
        logs.events = logs.events.filter { it.eventName != "statsig::diagnostics" }.toTypedArray()
        logs.events.sortBy { it.time }
        return@runBlocking logs.events
    }

    private fun generateRandomUserId(): String {
        return "user_${java.util.UUID.randomUUID()}"
    }
}
