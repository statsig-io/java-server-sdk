package com.statsig.sdk

import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test

class LayerTest {
    private lateinit var layer: Layer

    @Before
    fun setup() {
        layer = Layer("a_config", value = TestUtil.getConfigTestValues())
    }

    @Test
    fun testDummy() {
        val dummyLayer = Layer("", value = mapOf())
        assertEquals("provided default", dummyLayer.getString("test", "provided default"))
        assertEquals(true, dummyLayer.getBoolean("test", true))
        assertEquals(12, dummyLayer.getInt("test", 12))
        assertEquals("hello world", dummyLayer.getString("test", "hello world"))
        assertEquals(null, dummyLayer.ruleID)
        assertNull(dummyLayer.getString("test", null))
        assertNull(dummyLayer.getConfig("nested"))
        assertNull(dummyLayer.getString("testnodefault", null))
        assertNull(dummyLayer.getArray("testnodefault", null))
        assertNull(dummyLayer.getDictionary("testnodefault", null))
    }

    @Test
    fun testEmpty() {
        val emptyLayer = Layer(
            "test_layer",
            value = mapOf(),
            ruleID = "default",
        )

        assertEquals("provided default", emptyLayer.getString("test", "provided default"))
        assertEquals(12, emptyLayer.getInt("testInt", 12))
        assertEquals(true, emptyLayer.getBoolean("test_config", true))
        assertEquals(3.0, emptyLayer.getDouble("test_config", 3.0), 0.0)
        val arr = arrayOf("test", "one")
        assertArrayEquals(arr, emptyLayer.getArray("test_config", arr as Array<Any>))
        assertEquals("default", emptyLayer.ruleID)
        assertNull(emptyLayer.getConfig("nested"))
        assertNull(emptyLayer.getString("testnodefault", null))
        assertNull(emptyLayer.getArray("testnodefault", null))
        assertNull(emptyLayer.getDictionary("testnodefault", null))
    }

    @Test
    fun testPrimitives() {
        assertEquals("test", layer.getString("testString", "1234"))
        assertTrue(layer.getBoolean("testBoolean", false))
        assertEquals(12, layer.getInt("testInt", 13))
        assertEquals(42.3, layer.getDouble("testDouble", 13.0), 0.0)
        assertEquals(9223372036854775806, layer.getLong("testLong", 1))
    }

    @Test
    fun testArrays() {
        assertArrayEquals(arrayOf("one", "two"), layer.getArray("testArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(3L, 2L), layer.getArray("testIntArray", arrayOf(1, 2)))
        assertArrayEquals(arrayOf(3.1, 2.1), layer.getArray("testDoubleArray", arrayOf(1, "one")))
        assertArrayEquals(arrayOf(true, false), layer.getArray("testBooleanArray", arrayOf(1, "one")))
    }

    @Test
    fun testNested() {
        assertEquals("nested", layer.getConfig("testNested")!!.getString("nestedString", "111"))
        assertTrue(layer.getConfig("testNested")!!.getBoolean("nestedBoolean", false))
        assertEquals(13.74, layer.getConfig("testNested")!!.getDouble("nestedDouble", 99.99), 0.0)
        assertEquals(13, layer.getConfig("testNested")!!.getInt("nestedInt", 13))
        assertNull(layer.getConfig("testNested")!!.getConfig("testNestedAgain"))

        assertEquals(
            mapOf(
                "nestedString" to "nested",
                "nestedBoolean" to true,
                "nestedDouble" to 13.74,
                "nestedLong" to 13L
            ),
            layer.getDictionary("testNested", mapOf())
        )
    }
}
