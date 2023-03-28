package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class LockableArrayTest {
    private lateinit var arr: LockableArray<String>

    @Before
    fun setup() {
        arr = LockableArray<String>()
        arr.add("1")
        arr.add("2")
    }

    @Test
    fun testGettingTheSize() = runBlocking {
        assertEquals(2, arr.size())
    }

    @Test
    fun testGettingUnderlyingArray()  {
        assertEquals(arrayListOf("1", "2"), arr.reset())
    }
}
