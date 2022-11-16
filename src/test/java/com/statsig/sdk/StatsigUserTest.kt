package com.statsig.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test


class StatsigUserTest {

    @Test
    fun testInitializeUserID() {
        val userID = "abc123"
        val user = StatsigUser(userID)
        assertEquals(user.userID, "abc123")
        assertNull(user.customIDs)
    }

    @Test
    fun testInitializeCustomID() {
        val customIDs = mapOf<String, String>("customID" to "abc123")
        val user = StatsigUser(customIDs)
        assertNull(user.userID)
        assertEquals(user.customIDs, customIDs)
    }

    @Test
    fun testGetCopyForLogging() {
        val customIDs = mapOf<String, String>("customID" to "abc123")
        var user = StatsigUser(customIDs)

        var copy = user.getCopyForLogging()
        assertNull(copy.userID)
        assertEquals(copy.customIDs, customIDs)

        var userID = "abc123"
        var userUserID = StatsigUser(userID)

        copy = userUserID.getCopyForLogging()
        assertEquals(copy.userID, userID)
        assertNull(copy.customIDs)
    }
}
