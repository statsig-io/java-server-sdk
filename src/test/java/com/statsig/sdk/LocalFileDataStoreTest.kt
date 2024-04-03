package com.statsig.sdk

import com.statsig.sdk.datastore.LocalFileDataStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File

class LocalFileDataStoreTest {
    private lateinit var localDataStore: LocalFileDataStore
    private lateinit var downloadConfigSpecsResponse: String
    private lateinit var statsigServer: StatsigServer
    private lateinit var options: StatsigOptions
    private lateinit var mockServer: MockWebServer
    private var didCallDownloadConfig = false
    private val user = StatsigUser("test-user")

    @Before
    fun setUp() {
        downloadConfigSpecsResponse = StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""

        mockServer = MockWebServer()
        mockServer.start(8899)
        mockServer.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == null) {
                        return MockResponse().setResponseCode(404)
                    }
                    if ("/v1/download_config_specs" in request.path!!) {
                        didCallDownloadConfig = true
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    return MockResponse().setResponseCode(404)
                }
            }
        }

        localDataStore = LocalFileDataStore()
    }

    @After
    fun tearDown() {
        File(localDataStore.workingDirectory).deleteRecursively() // clean up the folder when finished tests
        mockServer.shutdown()
    }

    @Test
    fun testLocalDataStoreIsLoaded() {
        options = StatsigOptions(
            api = mockServer.url("/v1").toString(),
            dataStore = localDataStore,
            disableDiagnostics = true,
        )

        statsigServer = StatsigServer.create()
        statsigServer.initializeAsync("test-key", options).get()

        val gateRes1 = statsigServer.checkGateSync(user, "always_on_gate")
        Assert.assertTrue(gateRes1)

        user.email = "test@statsig.com"
        val gateRes2 = statsigServer.checkGateSync(user, "on_for_statsig_email")
        Assert.assertTrue(gateRes2)
        statsigServer.shutdown()
    }

    @Test
    fun testNetworkNotCallWhenBootstrapIsPresent() {
        options = StatsigOptions(
            api = mockServer.url("/v1").toString(),
            bootstrapValues = downloadConfigSpecsResponse,
        )
        statsigServer = StatsigServer.create()
        statsigServer.initializeAsync("secret-local", options).get()

        Assert.assertFalse(didCallDownloadConfig)
    }

    @Test
    fun testCallsNetworkWhenAdapterIsEmpty() {
        val options = StatsigOptions(
            api = mockServer.url("/v1").toString(),
        )
        statsigServer = StatsigServer.create()
        statsigServer.initializeAsync("secret-local", options).get()

        Assert.assertTrue(didCallDownloadConfig)
        statsigServer.shutdown()
    }

    @Test
    fun testNetworkNotCalledWhenAdapterEnable() {
        // if dataStore(cached one) is enabled
        // should not trigger network request
        val options = StatsigOptions(
            api = mockServer.url("/v1").toString(),
            dataStore = TestDataAdapter(),
        )
        statsigServer = StatsigServer.create()
        statsigServer.initializeAsync("secret-local", options).get()

        Assert.assertFalse(didCallDownloadConfig)

        // Test dataStore still works
        val dataStoreGateRes = statsigServer.checkGateSync(user, "gate_from_adapter_always_on")
        Assert.assertTrue(dataStoreGateRes)
        statsigServer.shutdown()
    }
}
