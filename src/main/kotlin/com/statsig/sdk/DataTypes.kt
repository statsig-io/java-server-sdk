package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

internal data class APIDownloadedConfigs(
    @SerializedName("dynamic_configs") val dynamicConfigs: Array<APIConfig>,
    @SerializedName("feature_gates") val featureGates: Array<APIConfig>,
    @SerializedName("layer_configs") val layerConfigs: Array<APIConfig>,
    @SerializedName("id_lists") val idLists: Map<String, Boolean>?,
    @SerializedName("layers") val layers: Map<String, Array<String>>?,
    @SerializedName("time") val time: Long = 0,
    @SerializedName("has_updates") val hasUpdates: Boolean,
    @SerializedName("sdk_keys_to_app_ids") val sdkKeysToAppIDs: Map<String, String>? = null,
    @SerializedName("diagnostics") val diagnostics: Map<String, Int>? = null,
    @SerializedName("hashed_sdk_keys_to_app_ids") val hashedSDKKeysToAppIDs: Map<String, String>? = null,
    @SerializedName("hashed_sdk_key_used") val hashedSDKKeyUsed: String? = null,
    @SerializedName("hashed_sdk_keys_to_entities") val hashedSDKKeysToEntities: Map<String, APIEntityNames>? = null,
    @SerializedName("sdk_flags") val sdkFlags: Map<String, Boolean>? = null,
    @SerializedName("sdk_configs") val sdkConfigs: Map<String, Any>? = null,
    @SerializedName("app_id") val primaryTargetAppID: String? = null,
)

internal data class APIEntityNames(
    @SerializedName("configs") val configs: Array<String>,
    @SerializedName("gates") val gates: Array<String>,
)

internal data class APIConfig(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("isActive") val isActive: Boolean,
    @SerializedName("salt") val salt: String,
    @SerializedName("defaultValue") val defaultValue: Any,
    @SerializedName("enabled") val enabled: Boolean,
    @SerializedName("rules") val rules: Array<APIRule>,
    @SerializedName("idType") val idType: String,
    @SerializedName("entity") val entity: String,
    @SerializedName("explicitParameters") val explicitParameters: Array<String>?,
    @SerializedName("hasSharedParams") val hasSharedParams: Boolean?,
    @SerializedName("targetAppIDs") val targetAppIDs: Array<String>? = null,
    @SerializedName("version") val version: Long? = 0,
    @SerializedName("forwardAllExposures") val forwardAllExposures: Boolean?,
)

internal data class APIRule(
    @SerializedName("name") val name: String,
    @SerializedName("passPercentage") val passPercentage: Double,
    @SerializedName("returnValue") val returnValue: Any,
    @SerializedName("id") val id: String,
    @SerializedName("salt") val salt: String?,
    @SerializedName("conditions") val conditions: Array<APICondition>,
    @SerializedName("idType") val idType: String,
    @SerializedName("groupName") val groupName: String?,
    @SerializedName("configDelegate") val configDelegate: String?,
    @SerializedName("isExperimentGroup") val isExperimentGroup: Boolean?,
    @SerializedName("samplingRate") val samplingRate: Long?,
) {
    fun isTargetingRule(): Boolean {
        return id == "targetingGate" || id == "inlineTargetingRules"
    }

    fun isOverrideRule(): Boolean {
        return id.endsWith("override")
    }
}

internal data class APICondition(
    @SerializedName("type") val type: String,
    @SerializedName("targetValue") val targetValue: Any?,
    @SerializedName("operator") val operator: String?,
    @SerializedName("field") val field: String?,
    @SerializedName("additionalValues") val additionalValues: Map<String, Any>?,
    @SerializedName("idType") val idType: String,
    @Transient
    var segmentIdSet: Set<String>? = null
)

data class APIFeatureGate(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Boolean,
    @SerializedName("rule_id") val ruleID: String?,
    @SerializedName("secondary_exposures")
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    val reason: EvaluationReason?,
    val evaluationDetails: EvaluationDetails?,
    @SerializedName("id_type") val idType: String?,
)

internal data class APIDynamicConfig(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: Map<String, Any>,
    @SerializedName("rule_id") val ruleID: String? = "",
    @SerializedName("secondary_exposures")
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    @SerializedName("undelegated_secondary_exposures")
    val undelegatedSecondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
)

internal data class IDList(
    @SerializedName("name") val name: String,
    @SerializedName("size") var size: Long = 0,
    @SerializedName("creationTime") val creationTime: Long = 0,
    @SerializedName("url") val url: String? = null,
    @SerializedName("fileID") val fileID: String? = null,
) {
    internal val ids: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun contains(id: String): Boolean {
        return ids.contains(id)
    }

    fun add(id: String) {
        ids.add(id)
    }

    fun remove(id: String) {
        ids.remove(id)
    }
}

internal data class LayerExposureMetadata(
    @SerializedName("config") val config: String,
    @SerializedName("ruleID") val ruleID: String,
    @SerializedName("allocatedExperiment") val allocatedExperiment: String,
    @SerializedName("parameterName") val parameterName: String,
    @SerializedName("isExplicitParameter") val isExplicitParameter: String,
    @SerializedName("secondaryExposures") val secondaryExposures: ArrayList<Map<String, String>>,
    @SerializedName("isManualExposure") var isManualExposure: String = "false",
    @SerializedName("evaluationDetails") val evaluationDetails: EvaluationDetails?,
    @SerializedName("configVersion") val configVersion: Long? = null,
) {
    fun toStatsigEventMetadataMap(): MutableMap<String, String> {
        var map = mutableMapOf(
            "config" to config,
            "ruleID" to ruleID,
            "allocatedExperiment" to allocatedExperiment,
            "parameterName" to parameterName,
            "isExplicitParameter" to isExplicitParameter,
            "isManualExposure" to isManualExposure,
            // secondaryExposures excluded -- StatsigEvent adds secondaryExposures explicitly as a top level key
        )
        if (configVersion != null) {
            map["configVersion"] = configVersion.toString()
        }
        return map
    }
}
