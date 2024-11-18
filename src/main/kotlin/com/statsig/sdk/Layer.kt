package com.statsig.sdk

typealias OnLayerExposure = (layerExposureEventData: LayerExposureEventData) -> Unit
internal typealias OnLayerExposureInternal = (layer: Layer, parameterName: String) -> Unit

/**
 * A helper class for interfacing with Layers defined in the Statsig console
 */
class Layer internal constructor(
    val name: String,
    val ruleID: String? = null,
    val groupName: String? = null,
    val value: Map<String, Any>,
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    val allocatedExperiment: String? = null,
    val evaluationDetails: EvaluationDetails? = null,
    private val onExposure: OnLayerExposureInternal? = null,
) {

    companion object {
        fun empty(name: String): Layer {
            return Layer(name, null, null, mapOf())
        }
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getString(key: String, default: String?): String? {
        return get(key, default, value)
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getBoolean(key: String, default: Boolean): Boolean {
        return get(key, default, value)
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDouble(key: String, default: Double): Double {
        return get<Number>(key, default, value).toDouble()
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getInt(key: String, default: Int): Int {
        return get<Number>(key, default, value).toInt()
    }

    /**
     * Gets a value from the layer, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getLong(key: String, default: Long): Long {
        return get<Number>(key, default, value).toLong()
    }

    /**
     * Gets a value from the config, falling back to the provided default value
     * @param key the index within the Layer to fetch a value from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getArray(key: String, default: Array<*>?): Array<*>? {
        var res = value[key] as? Array<*>
        if (res == null) {
            res = (value[key] as? ArrayList<*>)?.toTypedArray()
        }

        if (res != null) {
            logParameterExposure(key)
        }

        return res ?: default
    }

    /**
     * Gets a dictionary from the config, falling back to the provided default value
     * @param key the index within the Layer to fetch a dictionary from
     * @param default the default value to return if the expected key does not exist in the config
     * @return the value at the given key, or the default value if not found
     */
    fun getDictionary(key: String, default: Map<String, Any>?): Map<String, Any>? {
        return get(key, default, value)
    }

    /**
     * Gets a value from the config as a new DynamicConfig, or null if not found
     * @param key the index within the Layer to fetch a value from
     * @return the value at the given key as a DynamicConfig, or null
     */
    fun getConfig(key: String): DynamicConfig? {
        return when (val value = get(key, null as Map<String, Any>?, value)) {
            is Map<String, Any> -> DynamicConfig(
                key,
                value,
                this.ruleID,
                this.groupName,
            )
            else -> null
        }
    }

    /**
     * Legacy exposure data at the layer level. Should NOT be used in new code as this will cause over exposure.
     */
    fun getLegacyExposureMetadata(): String {
        return Utils.PLAIN_GSON.toJson(
            mapOf(
                "config" to this.name,
                "ruleID" to this.ruleID,
                "secondaryExposures" to this.secondaryExposures,
                "allocatedExperiment" to this.allocatedExperiment,
            ),
        )
    }

    private fun logParameterExposure(key: String) {
        onExposure?.let {
            it(this, key)
        }
    }

    /**
     * We should not just expose this function as inline reified is copied to every place it is used.
     */
    private inline fun <reified T> get(key: String, default: T, jsonValue: Map<String, Any>): T {
        val value = jsonValue[key] as? T
        if (value != null) {
            logParameterExposure(key)
        }
        return value ?: default
    }
}

internal fun createLayerExposureMetadata(
    layer: Layer,
    parameterName: String,
    configEvaluation: ConfigEvaluation,
): LayerExposureMetadata {
    var allocatedExperiment = ""
    var exposures = configEvaluation.undelegatedSecondaryExposures
    val isExplicit = configEvaluation.explicitParameters?.contains(parameterName) ?: false
    if (isExplicit) {
        exposures = configEvaluation.secondaryExposures
        allocatedExperiment = configEvaluation.configDelegate ?: ""
    }

    return LayerExposureMetadata(
        layer.name,
        layer.ruleID ?: "",
        allocatedExperiment,
        parameterName,
        isExplicit.toString(),
        exposures,
        evaluationDetails = configEvaluation.evaluationDetails,
        configVersion = configEvaluation.configVersion,
    )
}
