package com.statsig.sdk

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OnDeviceEvalClientInitializeFormatterTest {
    private lateinit var driver: StatsigServer
    private lateinit var gson: Gson

    @Before
    fun setup() {
        driver = StatsigServer.create()
        gson = GsonBuilder().create()
        driver.initializeAsync("secret-local", StatsigOptions()).get()
    }

    @Test

    fun testSerialization() {
        val dcs = this::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
        val specStore = TestUtilJava.getSpecStoreFromStatsigServer(driver)
        val configs: APIDownloadedConfigs =
            gson.fromJson(dcs, APIDownloadedConfigs::class.java)
        specStore.setDownloadedConfigs(configs)

        val formatter = OnDeviceEvalClientInitializeFormatter(specStore, clientSDKKey = "client-sdk")
        val response = formatter.getFormattedResponse()
        val serializedResponse = gson.toJson(response)
        assertNotNull(serializedResponse)
        assertTrue(serializedResponse is String)
        assertTrue(serializedResponse.isNotEmpty())

        val actualJson = gson.fromJson(serializedResponse, Map::class.java) as Map<String, Any>
        val expectedJson = gson.fromJson(dcs, Map::class.java) as Map<String, Any>

        val actualFeatureGatesMap = actualJson["feature_gates"] as Map<String, Any>
        val expectedFeatureGatesList = expectedJson["feature_gates"] as List<Map<String, Any>>
        val expectedFeatureGatesMap = expectedFeatureGatesList.associateBy { it["name"] as String }

        val gateName = "always_on_gate"
        val actualGate = actualFeatureGatesMap[gateName] as Map<String, Any>
        val expectedGate = expectedFeatureGatesMap[gateName] as Map<String, Any>

        val actualRules = actualGate["rules"] as List<Map<String, Any>>
        val expectedRules = expectedGate["rules"] as List<Map<String, Any>>

        assertEquals(expectedGate["name"], actualGate["name"])
        assertEquals(expectedGate["type"], actualGate["type"])
        assertEquals(expectedGate["salt"], actualGate["salt"])
        assertEquals(expectedGate["enabled"], actualGate["enabled"])
        assertEquals(expectedGate["defaultValue"], actualGate["defaultValue"])

        assertEquals(expectedRules.size, actualRules.size)

        for (i in expectedRules.indices) {
            val expectedRule = expectedRules[i]
            val actualRule = actualRules[i]

            assertEquals(expectedRule["name"], actualRule["name"])
            assertEquals(expectedRule["groupName"], actualRule["groupName"])
            assertEquals(expectedRule["passPercentage"], actualRule["passPercentage"])
            assertEquals(expectedRule["returnValue"], actualRule["returnValue"])
            assertEquals(expectedRule["id"], actualRule["id"])
            assertEquals(expectedRule["salt"], actualRule["salt"])

            val actualConditions = actualRule["conditions"] as List<Map<String, Any>>
            val expectedConditions = expectedRule["conditions"] as List<Map<String, Any>>

            assertEquals(expectedConditions.size, actualConditions.size)

            for (j in expectedConditions.indices) {
                val expectedCondition = expectedConditions[j]
                val actualCondition = actualConditions[j]

                assertEquals(expectedCondition["type"], actualCondition["type"])
                assertEquals(expectedCondition["targetValue"], actualCondition["targetValue"])
                assertEquals(expectedCondition["operator"], actualCondition["operator"])
                assertEquals(expectedCondition["field"], actualCondition["field"])
                assertEquals(expectedCondition["additionalValues"], actualCondition["additionalValues"])
            }
        }
    }

    @Test
    fun setDownloadedConfigsAndTestOnDeviceFormatter() {
        val specStore = TestUtilJava.getSpecStoreFromStatsigServer(driver)
        val input = APIDownloadedConfigs(
            dynamicConfigs = arrayOf(createAPIConfig("dynamicConfig")),
            featureGates = arrayOf(createAPIConfig("featureGate")),
            layerConfigs = arrayOf(createAPIConfig("layer")),
            idLists = emptyMap(),
            layers = mapOf("layer" to arrayOf("experiment1", "experiment2")),
            time = 420L,
            hasUpdates = true
        )
        specStore.setDownloadedConfigs(input)

        val formatter = OnDeviceEvalClientInitializeFormatter(specStore, "valid-sdk-key")
        val response = formatter.getFormattedResponse()

        assertEquals(1, response.feature_gates.size)
        assertEquals("dynamicConfig", response.dynamic_configs["dynamicConfig"]?.name)
        assertEquals(false, response.dynamic_configs["dynamicConfig"]?.isActive)
        assertEquals("featureGate", response.feature_gates["featureGate"]?.name)
        assertEquals(false, response.feature_gates["featureGate"]?.isActive)
        assertEquals("layer", response.layer_configs["layer"]?.name)
        assertEquals(420L, response.time)
        assertEquals("java-server", response.sdkInfo["sdkType"])
    }

    private fun createAPIConfig(name: String): APIConfig {
        return APIConfig(
            name,
            "",
            false,
            "",
            "",
            false,
            emptyArray(),
            "",
            "",
            null,
            null,
        )
    }
}
