package com.statsig.sdk.datastore

abstract class IDataStore {
    abstract fun get(key: String): String?
    abstract fun set(key: String, value: String)
    abstract fun shutdown()
}
