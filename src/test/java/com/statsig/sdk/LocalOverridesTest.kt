package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class LocalOverridesTest {
    private val userA = StatsigUser(userID = "user-a")
    private val userB = StatsigUser(userID = "user-b")

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
        assertFalse(Statsig.checkGate(userA, "override_me"))
        Statsig.overrideGate("override_me", true)
        assertTrue(Statsig.checkGate(userA, "override_me"))
        assertTrue(Statsig.checkGate(userB, "override_me"))
        Statsig.overrideGate("override_me", false)
        assertFalse(Statsig.checkGate(userB, "override_me"))
    }

    @Test
    fun testConfigOverrides() = runBlocking {
        val emptyMap = mapOf<String, Any>()

        assertEquals(Statsig.getConfig(userA, "override_me").value, emptyMap)

        var overriddenValue = mapOf("hello" to "its me")
        Statsig.overrideConfig("override_me", overriddenValue)

        assertEquals(Statsig.getConfig(userA, "override_me").value, overriddenValue)

        overriddenValue = mapOf("hello" to "its no longer me")
        Statsig.overrideConfig("override_me", overriddenValue)
        assertEquals(Statsig.getConfig(userB, "override_me").value, overriddenValue)

        Statsig.overrideConfig("override_me", emptyMap)
        assertEquals(Statsig.getConfig(userB, "override_me").value, emptyMap)
    }
}