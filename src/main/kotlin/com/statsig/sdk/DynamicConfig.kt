package com.statsig.sdk

import com.google.gson.Gson

/**
 * A helper class for interfacing with Dynamic Configs defined in the Statsig console
 */
class DynamicConfig(
    val name: String,
    val value: Map<String, Any>,
    val ruleID: String? = null,
    val groupName: String? = null,
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf()
) {
    internal companion object {
        fun empty(name: String = ""): DynamicConfig {
            return DynamicConfig(name, mapOf())
        }
    }

    init { }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getString(key: String, default: String?): String? {
        return when (val res = value[key]) {
            is String -> res
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getBoolean(key: String, default: Boolean): Boolean {
        return when (val res = value[key]) {
            is Boolean -> res
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDouble(key: String, default: Double): Double {
        return when (val res = value[key]) {
            is Number -> res.toDouble()
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getInt(key: String, default: Int): Int {
        return when (val res = value[key]) {
            is Number -> res.toInt()
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getLong(key: String, default: Long): Long {
        return when (val res = value[key]) {
            is Number -> res.toLong()
            else -> default
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<*>?): Array<*>? {
        return when (val value = this.value[key]) {
            is Array<*> -> value
            is ArrayList<*> -> value.toTypedArray()
            else -> default
        }
    }

    /**
     * Gets a dictionary from the config, falling back to the provided default value
     * @param key the index within the DynamicConfig to fetch a dictionary from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDictionary(key: String, default: Map<String, Any>?): Map<String, Any>? {
        return when (this.value[key]) {
            is Map<*, *> -> this.value[key] as Map<String, Any>
            else -> default
        }
    }

    /**
     * Gets a value from the config as a new DynamicConfig, or null if not found
     * @param key the index within the DynamicConfig to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? {
        return when (this.value[key]) {
            is Map<*, *> -> DynamicConfig(

                key,
                this.value[key] as Map<String, Any>,
                this.ruleID,
                this.groupName,
            )
            else -> null
        }
    }

    fun getExposureMetadata(): String {
        return Gson().toJson(
            mapOf(
                "config" to this.name,
                "ruleID" to this.ruleID,
                "secondaryExposures" to this.secondaryExposures
            )
        )
    }
}
