package com.statsig.sdk

abstract class IDataStore {
    abstract fun get(key: String): String?
    abstract fun set(key: String, value: String)
    abstract fun shutdown()
}
