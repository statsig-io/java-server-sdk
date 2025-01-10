package com.statsig.sdk

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class ThreadLocalHashingTest {

    @Test
    fun testSingleSha256ToLong() {
        val input = "test"
        val expected = generateSha256ToLongExpected(input)
        val result = Hashing.sha256ToLong(input)
        assertEquals(expected, result)
    }

    @Test
    fun testConcurrentSha256ToLong() {
        val executor = Executors.newFixedThreadPool(10)
        val tasks = List(10) {
            Runnable {
                repeat(100) {
                    val input = "test$it"
                    val expected = generateSha256ToLongExpected(input)
                    val result = Hashing.sha256ToLong(input)
                    assertEquals(expected, result)
                }
            }
        }
        tasks.forEach { executor.submit(it) }
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.MINUTES)
    }

    private fun generateSha256ToLongExpected(input: String): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(hashBytes)
        val high = buffer.long
        val low = buffer.long

        return (high xor low).absoluteValue
    }
}
