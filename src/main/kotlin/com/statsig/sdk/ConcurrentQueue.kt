package com.statsig.sdk

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ConcurrentQueue<T> {
    private val queue = ConcurrentLinkedQueue<T>()
    private val sizeCounter = AtomicInteger(0)

    fun size(): Int {
        return sizeCounter.get()
    }

    fun add(value: T) {
        queue.add(value)
        sizeCounter.incrementAndGet()
    }

    fun reset(): List<T> {
        val result = mutableListOf<T>()
        var count = 0

        while (true) {
            val item = queue.poll() ?: break
            result.add(item)
            count++
        }

        sizeCounter.addAndGet(-count).coerceAtLeast(0)

        return result
    }
}
