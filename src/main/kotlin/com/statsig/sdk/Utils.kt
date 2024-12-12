package com.statsig.sdk
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.reflect.TypeToken
import java.util.*

inline fun <reified T> Gson.fromJson(json: String): T = fromJson(json, object : TypeToken<T>() {}.type)

internal class Utils {
    companion object {
        fun getTimeInMillis(): Long {
            return System.currentTimeMillis()
        }
        val GSON: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
        val PLAIN_GSON = Gson()
        fun toStringOrEmpty(value: Any?): String {
            return value?.toString() ?: ""
        }
        fun sortMap(map: Map<*, *>): SortedMap<String, Any> {
            val sortedMap = TreeMap<String, Any>()
            for (key in map.keys) {
                val value = map[key]

                if (value == null) {
                    continue
                }
                if (key is String) {
                    if (value is Map<*, *>) {
                        sortedMap.put(key, sortMap(value))
                    } else {
                        sortedMap.put(key, value)
                    }
                }
            }
            return sortedMap
        }
    }
}
