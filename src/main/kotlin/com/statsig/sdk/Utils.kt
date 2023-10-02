package com.statsig.sdk
import java.util.SortedMap
import java.util.TreeMap

internal class Utils {
    companion object {
        fun getTimeInMillis(): Long {
            return System.currentTimeMillis()
        }

        fun toStringOrEmpty(value: Any?): String {
            return value?.toString() ?: ""
        }

        fun sortMap(map: Map<String, Any>): SortedMap<String, Any> {
            val sortedMap = TreeMap<String, Any>()
            for (key in map.keys) {
                val value = map[key]
                if (value == null) {
                    continue
                }
                if (value is Map<*, *>) {
                    sortedMap.put(key, sortMap(value as Map<String, Any>))
                } else {
                    sortedMap.put(key, value)
                }
            }
            return sortedMap
        }
    }
}
