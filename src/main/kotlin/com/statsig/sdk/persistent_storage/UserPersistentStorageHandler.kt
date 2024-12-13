package com.statsig.sdk.persistent_storage

import com.statsig.sdk.APIConfig
import com.statsig.sdk.StatsigUser

internal class UserPersistentStorageHandler(private val provider: IUserPersistentStorage?) {
    suspend fun loadSingleIDType(user: StatsigUser, idType: String): PersistedValues? {
        if (provider == null) {
            return null
        }
        val key = getStorageKey(user, idType)
        return mapOf(key to provider.load(key))
    }

    suspend fun loadMultipleIDTypes(user: StatsigUser, experiments: List<APIConfig>): PersistedValues? {
        if (provider == null) {
            return null
        }
        val experimentsByIDType = experiments.groupBy { it.idType }
        return experimentsByIDType.map { (key, value) ->
            val key = getStorageKey(user, key)
            key to provider.load(key, value.map { it.name })
        }.toMap()
    }

    fun save(user: StatsigUser, idType: String, name: String, data: StickyValues) {
        if (provider == null) {
            return
        }
        val key = getStorageKey(user, idType)
        provider.save(key, name, data)
    }

    fun delete(user: StatsigUser, idType: String, name: String) {
        if (provider == null) {
            return
        }
        val key = getStorageKey(user, idType)
        provider.delete(key, name)
    }

    companion object {
        fun getStorageKey(user: StatsigUser, idType: String): String {
            return "${user.getID(idType)}:$idType"
        }
    }
}
