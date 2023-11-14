package com.statsig.sdk

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StatsigOptionsTest {

    private lateinit var statsigOptions: StatsigOptions

    @Before
    fun setUp() {
        statsigOptions = StatsigOptions()
    }

    @Test
    fun testSetTier() {
        val tierTestCases = listOf(
            "PRODUCTION" to "production",
            "STAGING" to "staging",
            "Development" to "development",
            "Custom Tier" to "custom tier"
        )

        for ((inputTier, expectedTier) in tierTestCases) {
            statsigOptions.setTier(inputTier)
            assertEquals(expectedTier, statsigOptions.getEnvironment()?.get("tier"))
        }
    }

    @Test
    fun testSetEnvironmentParameter() {
        val parameterTestCases = mapOf(
            "key1" to "value1",
            "key2" to "value2",
            "key1" to "updatedValue1"
        )

        for ((key, value) in parameterTestCases) {
            statsigOptions.setEnvironmentParameter(key, value)
            assertEquals(value, statsigOptions.getEnvironment()?.get(key))
        }
    }

    @Test
    fun testInitializationWithApi() {
        val api = "https://custom.api.net"
        val options = StatsigOptions(api)
        assertEquals(api, options.api)
    }

    @Test
    fun testInitializationWithTimeout() {
        val timeout = 5000L
        val options = StatsigOptions(timeout)
        assertEquals(timeout, options.initTimeoutMs)
    }
}
