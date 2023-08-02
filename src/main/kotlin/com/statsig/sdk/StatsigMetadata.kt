package com.statsig.sdk

import java.util.Properties

private const val VERSION = "1.5.0"

internal class StatsigMetadata {
    companion object {
        private val version =
            try {
                val properties = Properties()
                properties.load(
                    StatsigMetadata::class.java.getResourceAsStream("/statsigsdk.properties"),
                )
                properties.getProperty("version")
            } catch (e: Exception) {
                VERSION
            }

        fun asMap(): Map<String, String> {
            return mapOf("sdkType" to "java-server", "sdkVersion" to version)
        }

        fun asJson(): String {
            val map = asMap()
            val values = map.map { "\"${it.key}\":\"${it.value}\"" }
            return "{${values.joinToString(",")}}"
        }
    }
}
