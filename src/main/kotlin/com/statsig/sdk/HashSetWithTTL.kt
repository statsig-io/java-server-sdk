package com.statsig.sdk

import kotlinx.coroutines.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class HashSetWithTTL(
    private val resetInterval: Long
) {
    private val set = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val lock = ReentrantReadWriteLock()

    init {
        startBackgroundReset()
    }

    fun add(key: String) {
        lock.write {
            set.add(key)
        }
    }

    fun contains(key: String): Boolean {
        lock.read {
            return set.contains(key)
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private fun reset() {
        lock.write {
            set.clear()
        }
    }

    private fun startBackgroundReset() {
        scope.launch {
            try {
                while (isActive) {
                    delay(resetInterval)
                    reset()
                }
            } catch (e: Exception) {
                // TODO
            }
        }
    }
}
