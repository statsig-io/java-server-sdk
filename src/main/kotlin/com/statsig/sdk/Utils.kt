package com.statsig.sdk

internal class Utils {
    companion object {
        fun getTimeInMillis(): Long {
            return System.currentTimeMillis()
        }

        fun toStringOrEmpty(value: Any?): String {
            return value?.toString() ?: ""
        }
    }
}
