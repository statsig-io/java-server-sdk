package com.statsig.sdk

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.CompletableDeferred
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test

private class TestDataAdapter : IDataStore() {
    var data =
        DataStoreTest::class.java.getResource("/data_adapter.json")?.readText() ?: ""
    var dataStore = mutableMapOf(
        STORAGE_ADAPTER_KEY to data,
    )
    override fun get(key: String): String? {
        return dataStore[key]
    }

    override fun set(key: String, value: String) {
        this.dataStore[key] = value
    }
    override fun shutdown() {
        return
    }
}

class DataStoreTest {

    lateinit var driver: StatsigServer
    private lateinit var specStore: SpecStore
    private lateinit var eventLogInputCompletable: CompletableDeferred<LogEventInput>
    private lateinit var server: MockWebServer
    lateinit var downloadConfigSpecsResponse: String
    lateinit var networkOptions: StatsigOptions
    var didCallDownloadConfig = false

    val user = StatsigUser("a_user")

    @Before
    fun setup() {
        downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""

        didCallDownloadConfig = false
        eventLogInputCompletable = CompletableDeferred()
        val gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

        server = MockWebServer()
        server.start(8899)
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    when (request.path) {
                        "/v1/download_config_specs" -> {
                            didCallDownloadConfig = true
                            if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                                throw Exception("No content type set!")
                            }
                            return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                        }
                        "/v1/log_event" -> {
                            val logBody = request.body.readUtf8()
                            eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput::class.java))
                            return MockResponse().setResponseCode(200)
                        }
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        }

        networkOptions = StatsigOptions(
            dataStore = TestDataAdapter(),
            api = server.url("/v1").toString(),
            disableDiagnostics = true,
        )
    }

    @After
    fun afterEach() {
        server.shutdown()
    }

    @Test
    fun dataStoreIsLoaded() {
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()

        val res = driver.checkGateAsync(user, "gate_from_adapter_always_on").get()
        Assert.assertTrue(res)
    }

    @Test
    fun testBootstrapIsIgnoredWhenDataStoreIsSet() {
        networkOptions = StatsigOptions(
            dataStore = TestDataAdapter(),
            api = server.url("/v1").toString(),
            bootstrapValues = downloadConfigSpecsResponse,
            disableDiagnostics = true,
        )
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()
        val dataStoreGateRes = driver.checkGateAsync(user, "gate_from_adapter_always_on").get()
        val bootstrapGateRes = driver.checkGateAsync(user, "always_on").get()
        driver.shutdown()

        val events = TestUtil.captureEvents(eventLogInputCompletable)
        Assert.assertEquals(2, events.size)

        Assert.assertEquals("DATA_ADAPTER", events[0].eventMetadata?.get("reason") ?: "")
        Assert.assertTrue(dataStoreGateRes)

        Assert.assertEquals("UNRECOGNIZED", events[1].eventMetadata?.get("reason") ?: "")
        Assert.assertFalse(bootstrapGateRes)
    }

    @Test
    fun testBootstrapIsIgnoredWhenBadDataStoreIsSet() {
        val invalidDataStore = TestDataAdapter()
        invalidDataStore.set(STORAGE_ADAPTER_KEY, "invalid_config_spec")
        networkOptions = StatsigOptions(
            dataStore = invalidDataStore,
            api = server.url("/v1").toString(),
            bootstrapValues = downloadConfigSpecsResponse,
            disableDiagnostics = true,
        )
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()
        val bootstrapGateRes = driver.checkGateAsync(user, "always_on").get()
        driver.shutdown()

        val events = TestUtil.captureEvents(eventLogInputCompletable)
        Assert.assertEquals("UNRECOGNIZED", events[0].eventMetadata?.get("reason") ?: "")
        Assert.assertFalse(bootstrapGateRes)
        Assert.assertTrue(didCallDownloadConfig)
    }

    @Test
    fun testCallsNetworkWhenAdapterIsEmpty() {
        val networkOptions = StatsigOptions(
            api = server.url("/v1").toString(),
        )
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()

        Assert.assertTrue(didCallDownloadConfig)
    }

    @Test
    fun testNetworkNotCalledWhenAdapterIsPresent() {
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()

        Assert.assertFalse(didCallDownloadConfig)
    }

    @Test
    fun testNetworkNotCallWhenBootstrapIsPresent() {
        networkOptions = StatsigOptions(
            api = server.url("/v1").toString(),
            bootstrapValues = downloadConfigSpecsResponse,
        )
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()

        Assert.assertFalse(didCallDownloadConfig)
    }

    @Test
    fun testNetworkCallWhenBootstrapIsInvalid() {
        networkOptions = StatsigOptions(
            api = server.url("/v1").toString(),
            bootstrapValues = "invalid bootstrap values",
        )
        driver = StatsigServer.create("secret-local", networkOptions)
        driver.initializeAsync().get()

        Assert.assertTrue(didCallDownloadConfig)
    }
}
