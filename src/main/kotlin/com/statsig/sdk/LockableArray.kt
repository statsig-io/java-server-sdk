package com.statsig.sdk

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class LockableArray<T> {
    private var array = arrayListOf<T>()
    private val lock = ReentrantReadWriteLock()

    fun size(): Int {
        return lock.write { array.size }
    }

    fun add(value: T) {
        lock.write {
            array.add(value)
        }
    }

    fun reset(): ArrayList<T> {
        lock.write {
            val flushed = array
            array = arrayListOf()
            return@reset flushed
        }
    }
}