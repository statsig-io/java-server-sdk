package com.statsig.sdk

import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConcurrentQueueTest {
    private lateinit var queue: ConcurrentQueue<String>

    @Before
    fun setup() {
        queue = ConcurrentQueue()
        queue.add("item1")
        queue.add("item2")
    }

    @Test
    fun testGettingTheSize() = runBlocking {
        assertEquals(2, queue.size())
    }

    @Test
    fun testResetAndGetUnderlyingArray() {
        val flushed = queue.reset()
        assertEquals(listOf("item1", "item2"), flushed)
        assertEquals(0, queue.size())
    }

    @Test
    fun testAddingElements() {
        queue.add("item3")
        assertEquals(3, queue.size())
    }

    @Test
    fun testConcurrentAdditions() = runBlocking {
        val jobs = List(10) { index ->
            launch {
                queue.add("item$index")
            }
        }
        jobs.joinAll()

        assertEquals(12, queue.size())
    }
}
