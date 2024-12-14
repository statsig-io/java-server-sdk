package com.statsig.sdk.persistent_storage

import com.statsig.sdk.StatsigUser

class UserPersistentStorageHandler(private val provider: IUserPersistentStorage?) {
    suspend fun load(user: StatsigUser, idType: String): UserPersistedValues? {
        if (provider == null) {
            return null
        }
        val key = getStorageKey(user, idType)
        return provider.load(key)
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
