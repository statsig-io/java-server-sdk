package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
    private val gson = Gson()

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

        localDataStore = LocalFileDataStore("/tmp/statsig/testfile.json", true)
    }

    @After
    fun tearDown() {
        File(localDataStore.filePath).parentFile.deleteRecursively() // clean up the folder when finished tests
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

        statsigServer = StatsigServer.create()
        statsigServer.initializeAsync("test-key", options).get()
        val gateRes3 = statsigServer.checkGateSync(user, "always_on_gate")
        Assert.assertTrue(gateRes3)
        statsigServer.shutdown()
    }

    @Test
    fun testLocalDataStoreCanDetectChange() {
        options = StatsigOptions(
            api = mockServer.url("/v1").toString(),
            dataStore = localDataStore,
            disableDiagnostics = true,
        )

        statsigServer = StatsigServer.create()
        statsigServer.initializeAsync("secret-local", options).get()

        val adapterKey = localDataStore.dataStoreKey

        val thread = Thread {
            val newGate = addNewGateToExistingSpecs() // add a new entry into Json file

            var existingContent = JsonParser.parseString(localDataStore.get(adapterKey)).asJsonObject
            var featureGates = existingContent.getAsJsonArray("feature_gates")
            featureGates?.add(newGate)

            val newTestUser = StatsigUser("newTestUser")
            newTestUser.email = "test@uw.edu"
            val fileOnChangeGateRes = statsigServer.checkGateSync(newTestUser, "add_new_gate")
            Assert.assertTrue(fileOnChangeGateRes)

            // Then delete this newly added entry to see if deletion can be detected as well
            existingContent = JsonParser.parseString(localDataStore.get("dummy-key")).asJsonObject
            featureGates = existingContent.getAsJsonArray("feature_gates")
            val updatedFeatureGates = featureGates.filter { it.asJsonObject.get("name").asString != "add_new_gate" }

            val updatedFeatureGatesArray = JsonArray()
            updatedFeatureGates.forEach { updatedFeatureGatesArray.add(it) }

            existingContent.remove("feature_gates") // Remove existing feature gates array
            existingContent.add("feature_gates", updatedFeatureGatesArray) // Add the updated feature gates array

            val checkGateResOnDeletion = statsigServer.checkGateSync(newTestUser, "add_new_gate")
            Assert.assertFalse(checkGateResOnDeletion)

            user.email = "test@statsig.com"
            val gateRes2 = statsigServer.checkGateSync(user, "on_for_statsig_email")
            Assert.assertTrue(gateRes2)
        }
        thread.start()
        thread.join()
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
        statsigServer.shutdown()
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

    private fun addNewGateToExistingSpecs(): JsonObject {
        val newGateJson = """{
          "name": "add_new_gate",
          "type": "feature_gate",
          "salt": "random_salt",
          "defaultValue": true, // can be false
          "enabled": true,
          "rules": [
            {
              "name": "7w9rbTSffLT89pxqpyhuqK",
              "passPercentage": 100.0,
              "returnValue": true,
              "id": "random_id",
              "salt": "e452510f-bd5b-42cb-a71e-00498a7903fc",
              "conditions": [
                {
                  "type": "user_field",
                  "targetValue": [
                    "@uw.edu"
                  ],
                  "operator": "str_contains_any",
                  "field": "email",
                  "additionalValues": {}
                }
              ],
              "groupName": "on for uw emails"
            }
          ],
          "entity": "feature_gate"
        }
        """.trimIndent()

        val newGate = gson.fromJson(newGateJson, JsonObject::class.java)
        return newGate
    }
}
