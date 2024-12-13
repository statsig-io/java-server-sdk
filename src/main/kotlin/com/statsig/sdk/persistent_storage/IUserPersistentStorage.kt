package com.statsig.sdk.persistent_storage

// Map of users to their respective persisted values
typealias PersistedValues = Map<String, UserPersistedValues>
// Map of experiments/layers to StickyValues for a given user
typealias UserPersistedValues = Map<String, StickyValues>

interface IUserPersistentStorage {
    /**
     * Returns the full map of persisted values for a specific user key
     * @param key user key
     */
    suspend fun load(key: String): UserPersistedValues

    /**
     * Returns a filtered map of persisted values for a specific user key
     * @param key user key
     * @param names list of experiment/layer names
     */
    suspend fun load(key: String, names: List<String>? = null): UserPersistedValues {
        return load(key)
    }

    /**
     * Save the persisted values of a config given a specific user key
     * @param key user key
     * @param configName Name of the config/experiment
     * @param data Object representing the persistent assignment to store for the given user-config
     */
    fun save(key: String, configName: String, data: StickyValues)

    /**
     * Delete the persisted values of a config given a specific user key
     * @param key user key
     * @param configName Name of the config/experiment
     */
    fun delete(key: String, configName: String)
}
