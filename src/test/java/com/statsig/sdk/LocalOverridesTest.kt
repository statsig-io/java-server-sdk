package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class LocalOverridesTest {
    private val userA = StatsigUser(userID = "user-a")
    private val userB = StatsigUser(customIDs = mapOf("customID" to "user-b"))
    private val users = listOf<StatsigUser>(userA, userB)

    companion object {
        @BeforeClass
        @JvmStatic
        internal fun beforeAll() = runBlocking {
            val options = StatsigOptions(localMode = true)
            Statsig.initialize("secret-local", options)
        }

        @AfterClass
        @JvmStatic
        fun afterAll() {
            Statsig.shutdown()
        }
    }

    @Test
    fun testGateOverrides() = runBlocking {
        users.forEach { user -> testGateOverridesHelper(user) }
    }

    private fun testGateOverridesHelper(user: StatsigUser) = runBlocking {
        assertFalse(Statsig.checkGate(user, "override_me"))

        Statsig.overrideGate("override_me", true)
        assertTrue(Statsig.checkGate(user, "override_me"))

        Statsig.removeGateOverride("override_me")
        assertFalse(Statsig.checkGate(user, "override_me"))
    }
    @Test
    fun testConfigOverrides() = runBlocking {
        users.forEach { user -> testConfigOverridesHelper(user) }
    }
    private fun testConfigOverridesHelper(user: StatsigUser) = runBlocking {
        val emptyMap = mapOf<String, Any>()

        assertEquals(Statsig.getConfig(user, "override_me").value, emptyMap)

        var overriddenValue = mapOf("hello" to "its me")
        Statsig.overrideConfig("override_me", overriddenValue)
        assertEquals(Statsig.getConfig(user, "override_me").value, overriddenValue)

        Statsig.removeConfigOverride("override_me")
        assertEquals(Statsig.getConfig(user, "override_me").value, emptyMap)
    }

    @Test
    fun testLayerOverrides() = runBlocking {
        users.forEach { user -> testLayerOverridesHelper(user) }
    }
    private fun testLayerOverridesHelper(user: StatsigUser) = runBlocking {
        val emptyMap = mapOf<String, Any>()

        assertEquals(Statsig.getLayer(user, "override_me").value, emptyMap)

        var overriddenValue = mapOf("hello" to "its me")
        Statsig.overrideLayer("override_me", overriddenValue)
        assertEquals(Statsig.getLayer(user, "override_me").value, overriddenValue)

        Statsig.removeLayerOverride("override_me")
        assertEquals(Statsig.getLayer(user, "override_me").value, emptyMap)
    }
}
