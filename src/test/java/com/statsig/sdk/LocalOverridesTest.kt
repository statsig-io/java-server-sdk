package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalOverridesTest {
    private val userA = StatsigUser(userID = "user-a")
    private val userB = StatsigUser(customIDs = mapOf("customID" to "user-b"))
    private val users = listOf<StatsigUser>(userA, userB)

    @Before
    fun setup() = runBlocking {
        val options = StatsigOptions(localMode = true)
        val _detail = Statsig.initialize("secret-local", options)
    }

    @After
    fun afterEach() {
        Statsig.shutdown()
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
    fun testGateOverridesWithUsers() = runBlocking {
        assertFalse(Statsig.checkGate(userA, "override_me"))

        Statsig.overrideGate("override_me", true, userA.userID)
        assertTrue(Statsig.checkGate(userA, "override_me"))
        assertFalse(Statsig.checkGate(userB, "override_me"))

        Statsig.overrideGate("override_me", true, "user-b")
        assertTrue(Statsig.checkGate(userB, "override_me"))

        Statsig.removeGateOverride("override_me")
        assertTrue(Statsig.checkGate(userA, "override_me"))
        assertTrue(Statsig.checkGate(userB, "override_me"))

        Statsig.removeGateOverride("override_me", userA.userID)
        Statsig.removeGateOverride("override_me", "user-b")
        assertFalse(Statsig.checkGate(userA, "override_me"))
        assertFalse(Statsig.checkGate(userB, "override_me"))
    }

    @Test
    fun testConfigOverridesWithUsers() = runBlocking {
        val emptyMap = mapOf<String, Any>()

        assertEquals(Statsig.getConfig(userB, "override_me").value, emptyMap)

        val overriddenValue = mapOf("hello" to "its me")
        Statsig.overrideConfig("override_me", overriddenValue, "user-b")
        assertEquals(Statsig.getConfig(userB, "override_me").value, overriddenValue)
        assertEquals(Statsig.getConfig(userA, "override_me").value, emptyMap)

        Statsig.removeConfigOverride("override_me", "user-b")
        assertEquals(Statsig.getConfig(userB, "override_me").value, emptyMap)
    }

    @Test
    fun testConfigOverrides() = runBlocking {
        users.forEach { user -> testConfigOverridesHelper(user) }
    }
    private fun testConfigOverridesHelper(user: StatsigUser) = runBlocking {
        val emptyMap = mapOf<String, Any>()

        assertEquals(Statsig.getConfig(user, "override_me").value, emptyMap)

        val overriddenValue = mapOf("hello" to "its me")
        Statsig.overrideConfig("override_me", overriddenValue)
        assertEquals(Statsig.getConfig(user, "override_me").value, overriddenValue)

        Statsig.removeConfigOverride("override_me")
        assertEquals(Statsig.getConfig(user, "override_me").value, emptyMap)
    }

    @Test
    fun testLayerOverridesWithUsers() = runBlocking {
        val emptyMap = mapOf<String, Any>()

        assertEquals(Statsig.getLayer(userA, "override_me").value, emptyMap)

        val overriddenValue = mapOf("hello" to "its me")
        Statsig.overrideLayer("override_me", overriddenValue, userA.userID)
        assertEquals(Statsig.getLayer(userA, "override_me").value, overriddenValue)
        assertEquals(Statsig.getLayer(userB, "override_me").value, emptyMap)

        Statsig.overrideLayer("override_me", overriddenValue, "user-b")
        assertEquals(Statsig.getLayer(userB, "override_me").value, overriddenValue)

        Statsig.removeLayerOverride("override_me", userA.userID)
        Statsig.removeLayerOverride("override_me", "user-b")
        assertEquals(Statsig.getLayer(userA, "override_me").value, emptyMap)
        assertEquals(Statsig.getLayer(userB, "override_me").value, emptyMap)
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
