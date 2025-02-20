package com.statsig.sdk

import com.statsig.sdk.persistent_storage.StickyValues

class ConfigEvaluation(
    var booleanValue: Boolean = false,
    var jsonValue: Any? = null,
    var ruleID: String = Const.EMPTY_STR,
    var groupName: String? = null,
    var secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    var undelegatedSecondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    var explicitParameters: Array<String>? = null,
    var configDelegate: String? = null,
    var evaluationDetails: EvaluationDetails? = null,
    var isExperimentGroup: Boolean = false,
    var configVersion: Long? = null,
    var forwardAllExposures: Boolean = false,
    var samplingRate: Long? = null,
    var isActive: Boolean = false,
    var idType: String = Const.EMPTY_STR,
) {
    internal var isDelegate: Boolean = false

    fun addSecondaryExposure(exposure: Map<String, String>) {
        secondaryExposures.add(exposure)
        if (!isDelegate) {
            undelegatedSecondaryExposures.add(exposure)
        }
    }

    // Used to save to PersistentStorage
    fun toStickyValues(): StickyValues {
        return StickyValues(
            value = this.booleanValue,
            jsonValue = this.jsonValue,
            ruleID = this.ruleID,
            groupName = this.groupName,
            secondaryExposures = this.secondaryExposures,
            explicitParameters = this.explicitParameters,
            configDelegate = this.configDelegate,
            undelegatedSecondaryExposures = this.undelegatedSecondaryExposures,
            time = this.evaluationDetails?.configSyncTime ?: Utils.getTimeInMillis(),
        )
    }

    companion object {
        fun fromStickyValues(stickyValues: StickyValues, initTime: Long): ConfigEvaluation {
            val evalDetail = EvaluationDetails(
                stickyValues.time,
                initTime,
                EvaluationReason.PERSISTED,
            )
            return ConfigEvaluation(
                jsonValue = stickyValues.jsonValue,
                booleanValue = stickyValues.value,
                ruleID = stickyValues.ruleID,
                groupName = stickyValues.groupName,
                secondaryExposures = stickyValues.secondaryExposures,
                undelegatedSecondaryExposures = stickyValues.undelegatedSecondaryExposures,
                explicitParameters = stickyValues.explicitParameters,
                configDelegate = stickyValues.configDelegate,
                isExperimentGroup = true,
                evaluationDetails = evalDetail,
                isActive = true,
            )
        }
    }
}
