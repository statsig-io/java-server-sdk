package com.statsig.sdk

import com.google.gson.*
import com.google.gson.annotations.SerializedName
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal data class APIDownloadedConfigsFromLocalEval(
    @SerializedName("dynamic_configs") val dynamicConfigs: Array<APIConfig>,
    @SerializedName("feature_gates") val featureGates: Array<APIConfig>,
    @SerializedName("layer_configs") val layerConfigs: Array<APIConfig>,
    @SerializedName("layers") val layers: Map<String, Array<String>>?,
    @SerializedName("time") val time: Long = 0,
    @SerializedName("has_updates") val hasUpdates: Boolean,
    @SerializedName("diagnostics") val diagnostics: Map<String, Int>? = null,
)

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

        val formatter = OnDeviceEvalClientInitializeFormatter(specStore, null)
        val response = formatter.getFormattedResponse().toMap()
        val serializedResponse = gson.toJson(response)
        assertNotNull(serializedResponse)
        assertTrue(serializedResponse is String)
        assertTrue(serializedResponse.isNotEmpty())

        val actualJson = gson.fromJson(serializedResponse, APIDownloadedConfigsFromLocalEval::class.java)
        val expectedJson = configs

        // Compare the seven fields ( since local eval only have below fields)
        assertEquals(gson.toJson(expectedJson.dynamicConfigs), gson.toJson(actualJson.dynamicConfigs))
        assertEquals(gson.toJson(expectedJson.featureGates), gson.toJson(actualJson.featureGates))
        assertEquals(gson.toJson(expectedJson.layerConfigs), gson.toJson(actualJson.layerConfigs))
        assertEquals(gson.toJson(expectedJson.layers), gson.toJson(actualJson.layers))
        assertEquals(gson.toJson(expectedJson.time), gson.toJson(actualJson.time))
        assertEquals(gson.toJson(expectedJson.hasUpdates), gson.toJson(actualJson.hasUpdates))
        assertEquals(gson.toJson(expectedJson.diagnostics), gson.toJson(actualJson.diagnostics))
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
