package com.statsig.sdk

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DynamicConfigTest {
    private lateinit var config: DynamicConfig

    @Before
    fun setup() {
        config = DynamicConfig("a_config", TestUtil.getConfigTestValues())
    }

    @Test
    fun testDummy() {
        val dummyConfig = DynamicConfig("", mapOf())
        assertEquals("provided default", dummyConfig.getString("test", "provided default"))
        assertEquals(true, dummyConfig.getBoolean("test", true))
        assertEquals(12, dummyConfig.getInt("test", 12))
        assertEquals("hello world", dummyConfig.getString("test", "hello world"))
        assertEquals(0, dummyConfig.value.size)
        assertEquals(null, dummyConfig.ruleID)
        assertNull(dummyConfig.getString("test", null))
        assertNull(dummyConfig.getConfig("nested"))
        assertNull(dummyConfig.getString("testnodefault", null))
        assertNull(dummyConfig.getArray("testnodefault", null))
        assertNull(dummyConfig.getDictionary("testnodefault", null))
    }

    @Test
    fun testEmpty() {
        val emptyConfig = DynamicConfig(
            "test_config",
            mapOf(),
            "default",
        )

        assertEquals("provided default", emptyConfig.getString("test", "provided default"))
        assertEquals(12, emptyConfig.getInt("testInt", 12))
        assertEquals(true, emptyConfig.getBoolean("test_config", true))
        assertEquals(3.0, emptyConfig.getDouble("test_config", 3.0), 0.0)
        val arr = arrayOf("test", "one")
        assertArrayEquals(arr, emptyConfig.getArray("test_config", arr as Array<Any>))
        assertEquals("default", emptyConfig.ruleID)
        assertNull(emptyConfig.getConfig("nested"))
        assertNull(emptyConfig.getString("testnodefault", null))
        assertNull(emptyConfig.getArray("testnodefault", null))
        assertNull(emptyConfig.getDictionary("testnodefault", null))
    }

    @Test
    fun testPrimitives() {
        assertEquals("test", config.getString("testString", "1234"))
        assertTrue(config.getBoolean("testBoolean", false))
        assertEquals(12, config.getInt("testInt", 13))
        assertEquals(42.3, config.getDouble("testDouble", 13.0), 0.0)
        assertEquals(9223372036854775806, config.getLong("testLong", 1))
    }

    @Test
    fun testArrays() {
        assertArrayEquals(arrayOf("one", "two"), config.getArray("testArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(3L, 2L), config.getArray("testIntArray", arrayOf(1, 2)))
        assertArrayEquals(arrayOf(3.1, 2.1), config.getArray("testDoubleArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(true, false), config.getArray("testBooleanArray", arrayOf(1, "one")))
    }

    @Test
    fun testNested() {
        assertEquals("nested", config.getConfig("testNested")!!.getString("nestedString", "111"))
        assertTrue(config.getConfig("testNested")!!.getBoolean("nestedBoolean", false))
        assertEquals(13.74, config.getConfig("testNested")!!.getDouble("nestedDouble", 99.99), 0.0)
        assertEquals(13, config.getConfig("testNested")!!.getInt("nestedInt", 13))
        assertNull(config.getConfig("testNested")!!.getConfig("testNestedAgain"))

        assertEquals(
            mapOf(
                "nestedString" to "nested",
                "nestedBoolean" to true,
                "nestedDouble" to 13.74,
                "nestedLong" to 13L
            ),
            config.getDictionary("testNested", mapOf())
        )
    }
}
