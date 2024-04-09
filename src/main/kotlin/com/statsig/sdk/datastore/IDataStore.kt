package com.statsig.sdk.datastore

import com.statsig.sdk.StatsigOptions

const val STORAGE_ADAPTER_KEY = "statsig.cache"

abstract class IDataStore {
    open var dataStoreKey = STORAGE_ADAPTER_KEY

    abstract fun get(key: String): String?
    abstract fun set(key: String, value: String)
    abstract fun shutdown()
    open fun setStatsigOptions(options: StatsigOptions) {}

    open fun shouldPollForUpdates(): Boolean = false // default is false
}
