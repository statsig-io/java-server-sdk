package com.statsig.sdk
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import java.util.SortedMap
import java.util.TreeMap

internal class Utils {
    companion object {
        fun getTimeInMillis(): Long {
            return System.currentTimeMillis()
        }
        fun getGson() = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
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
