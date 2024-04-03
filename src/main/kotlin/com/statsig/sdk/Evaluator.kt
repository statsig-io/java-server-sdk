package com.statsig.sdk

import ip3country.CountryLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ua_parser.Parser
import java.lang.IllegalStateException
import java.lang.Long.parseLong
import java.lang.NumberFormatException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Calendar
import java.util.Date
import kotlin.collections.set

internal class UnsupportedException(message: String) : Exception(message)

internal class ConfigEvaluation(
    var booleanValue: Boolean = false,
    var jsonValue: Any? = null,
    var ruleID: String = Const.EMPTY_STR,
    var groupName: String? = null,
    var secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    var explicitParameters: Array<String> = arrayOf(),
    var configDelegate: String? = null,
    var evaluationDetails: EvaluationDetails? = null,
    var isExperimentGroup: Boolean = false,
) {
    var undelegatedSecondaryExposures: ArrayList<Map<String, String>> = secondaryExposures
}

internal class Evaluator(
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private val statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary,
    private val diagnostics: Diagnostics,
    private val statsigMetadata: StatsigMetadata,
    private val sdkConfigs: SDKConfigs,
    private val serverSecret: String,
) {
    private var specStore: SpecStore
    private val uaParser: Parser by lazy {
        synchronized(this) {
            Parser()
        }
    }
    private var gateOverrides: MutableMap<String, Boolean> = HashMap()
    private var configOverrides: MutableMap<String, Map<String, Any>> = HashMap()
    private var layerOverrides: MutableMap<String, Map<String, Any>> = HashMap()
    private var hashLookupTable: MutableMap<String, ULong> = HashMap()

    private val calendarOne = Calendar.getInstance()
    private val calendarTwo = Calendar.getInstance()

    var isInitialized: Boolean = false

    init {
        CountryLookup.initialize()
        specStore = SpecStore(this.network, this.options, statsigMetadata, statsigScope, errorBoundary, diagnostics, sdkConfigs, serverSecret)
        network.setDiagnostics(diagnostics)
        statsigScope.launch {
            uaParser // This will cause the 'lazy' load to occur on a BG thread
        }
    }

    suspend fun initialize() {
        specStore.initialize()
        isInitialized = true
    }

    fun shutdown() {
        specStore.shutdown()
    }

    private fun createEvaluationDetails(reason: EvaluationReason): EvaluationDetails {
        if (reason == EvaluationReason.UNINITIALIZED) {
            return EvaluationDetails(0, 0, reason)
        }
        return EvaluationDetails(specStore.getLastUpdateTime(), specStore.getInitTime(), reason)
    }

    fun getVariants(configName: String): Map<String, Map<String, Any>> {
        val variants: MutableMap<String, Map<String, Any>> = HashMap()
        val config = specStore.getConfig(configName) ?: return variants

        var previousAllocation = 0.0
        for (r: APIRule in config.rules) {
            val value = r.returnValue.toString()
            val cond = r.conditions[0]
            var percent = 0.0
            if (cond.type.lowercase() == "user_bucket" && cond.targetValue is Number) {
                percent = (cond.targetValue.toDouble() - previousAllocation) / 1000.0
                previousAllocation = cond.targetValue.toDouble()
            }
            if (r.groupName != null) {
                variants[r.groupName] = mapOf("value" to value, "percent" to percent)
            }
        }
        return variants
    }

    // check if a user is overridden to any group for the experiment
    fun isUserOverriddenToExperiment(user: StatsigUser, expName: String): Boolean {
        val config = specStore.getConfig(expName) ?: return false
        for (rule in config.rules) {
            if (rule.id.contains("override", ignoreCase = true)) {
                val endResult = ConfigEvaluation()
                evaluateRule(user, rule, endResult)
                if (endResult.booleanValue) {
                    // user is overridden into the experiment
                    return true
                }
            }
        }
        return false
    }

    // check if a user is allocated to a specific experiment due to layer assignment
    fun isUserAllocatedToExperiment(user: StatsigUser, expName: String): Boolean {
        val config = specStore.getConfig(expName) ?: return false
        for (rule in config.rules) {
            if (rule.id.equals("layerAssignment", ignoreCase = true)) {
                val endResult = ConfigEvaluation()
                evaluateRule(user, rule, endResult)
                // user is in an experiment when they FAIL the layerAssignment rule
                return !endResult.booleanValue
            }
        }
        return false
    }

    fun overrideGate(gateName: String, gateValue: Boolean) {
        gateOverrides[gateName] = gateValue
    }

    fun overrideConfig(configName: String, configValue: Map<String, Any>) {
        configOverrides[configName] = configValue
    }

    fun overrideLayer(layerName: String, layerValue: Map<String, Any>) {
        layerOverrides[layerName] = layerValue
    }

    fun removeLayerOverride(layerName: String) {
        layerOverrides.remove(layerName)
    }

    fun removeConfigOverride(configName: String) {
        configOverrides.remove(configName)
    }

    fun removeGateOverride(gateName: String) {
        gateOverrides.remove(gateName)
    }

    fun getConfig(user: StatsigUser, dynamicConfigName: String, endResult: ConfigEvaluation) {
        if (configOverrides.containsKey(dynamicConfigName)) {
            endResult.jsonValue = configOverrides[dynamicConfigName] ?: mapOf<String, Any>()
            endResult.evaluationDetails = this.createEvaluationDetails((EvaluationReason.LOCAL_OVERRIDE))
            return
        }

        if (specStore.getEvaluationReason() == EvaluationReason.UNINITIALIZED) {
            endResult.evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED)
            return
        }

        this.evaluateConfig(user, specStore.getConfig(dynamicConfigName), endResult)
    }

    fun getClientInitializeResponse(
        user: StatsigUser,
        hash: HashAlgo = HashAlgo.SHA256,
        clientSDKKey: String? = null,
    ): ClientInitializeResponse {
        val response = ClientInitializeFormatter(
            this.specStore,
            this::evaluateConfig,
            user,
            hash,
            clientSDKKey,
        ).getFormattedResponse()
        if (response == null || response.isEmpty()) {
            val extraInfo = """{
                "hash": "$hash",
                "clientKey": "$clientSDKKey"
                }
            """.trimIndent()
            errorBoundary.logException(
                "getClientInitializeResponse",
                IllegalStateException("getClientInitializeResponse returns empty result: Possibly SDK failed to initialize"),
                extraInfo = extraInfo,
            )
        }
        return response
    }

    fun getLayer(user: StatsigUser, layerName: String, endResult: ConfigEvaluation) {
        if (layerOverrides.containsKey(layerName)) {
            val value = layerOverrides[layerName] ?: mapOf()
            endResult.jsonValue = value
            endResult.evaluationDetails = this.createEvaluationDetails(EvaluationReason.LOCAL_OVERRIDE)
            return
        }

        if (specStore.getEvaluationReason() == EvaluationReason.UNINITIALIZED) {
            endResult.evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED)
            return
        }

        this.evaluateConfig(user, specStore.getLayerConfig(layerName), endResult)
    }

    fun getExperimentsInLayer(layerName: String): Array<String> {
        return specStore.getLayer(layerName) ?: emptyArray()
    }

    fun checkGate(user: StatsigUser, gateName: String, endResult: ConfigEvaluation) {
        if (gateOverrides.containsKey(gateName)) {
            val value = gateOverrides[gateName] ?: false
            endResult.booleanValue = value
            endResult.jsonValue = value
            endResult.evaluationDetails = createEvaluationDetails(EvaluationReason.LOCAL_OVERRIDE)
            return
        }

        if (specStore.getEvaluationReason() == EvaluationReason.UNINITIALIZED) {
            endResult.evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED)
            return
        }

        val evalGate = specStore.getGate(gateName)
        this.evaluateConfig(user, evalGate, endResult)
    }

    private fun evaluateConfig(user: StatsigUser, config: APIConfig?, endResult: ConfigEvaluation) {
        if (config == null) {
            endResult.booleanValue = false
            endResult.secondaryExposures = arrayListOf()
            endResult.evaluationDetails = createEvaluationDetails(EvaluationReason.UNRECOGNIZED)
            return
        }

        this.evaluate(user, config, endResult)
    }

    private fun evaluate(user: StatsigUser, config: APIConfig, endResult: ConfigEvaluation) {
        val evaluationDetails = createEvaluationDetails(specStore.getEvaluationReason())
        if (!config.enabled) {
            endResult.booleanValue = false
            endResult.jsonValue = config.defaultValue
            endResult.ruleID = Const.DISABLED
            endResult.evaluationDetails = evaluationDetails
            return
        }

        for (rule in config.rules) {
            this.evaluateRule(user, rule, endResult)

            if (endResult.evaluationDetails?.reason == EvaluationReason.UNSUPPORTED) {
                return
            }

            endResult.evaluationDetails = evaluationDetails
            if (endResult.booleanValue) {
                if (this.evaluateDelegate(user, rule, endResult)) {
                    // if it's not null
                    return
                }

                val pass =
                    computeUserHash(
                        config.salt +
                            '.' +
                            (rule.salt ?: rule.id) +
                            '.' +
                            (getUnitID(user, rule.idType) ?: Const.EMPTY_STR),
                    )
                        .mod(10000UL) < (rule.passPercentage.times(100.0)).toULong()

                if (!pass) {
                    endResult.jsonValue = config.defaultValue
                }

                endResult.booleanValue = pass
                endResult.evaluationDetails = evaluationDetails
                endResult.isExperimentGroup = rule.isExperimentGroup ?: false
                return
            }
        }

        endResult.booleanValue = false
        endResult.jsonValue = config.defaultValue
        endResult.ruleID = Const.DEFAULT
        endResult.groupName = null
        endResult.evaluationDetails = evaluationDetails
    }

    private fun evaluateDelegate(
        user: StatsigUser,
        rule: APIRule,
        endResult: ConfigEvaluation,
    ): Boolean {
        val configDelegate = rule.configDelegate ?: return false
        val config = specStore.getConfig(configDelegate) ?: return false

        endResult.undelegatedSecondaryExposures = ArrayList(endResult.secondaryExposures)
        this.evaluate(user, config, endResult)

        endResult.configDelegate = rule.configDelegate
        endResult.explicitParameters = config.explicitParameters ?: arrayOf()
        endResult.evaluationDetails = this.createEvaluationDetails(this.specStore.getEvaluationReason())
        return true
    }

    private fun evaluateRule(user: StatsigUser, rule: APIRule, endResult: ConfigEvaluation) {
        var pass = true
        for (condition in rule.conditions) {
            try {
                if (!this.evaluateCondition(user, condition, endResult)) {
                    pass = false
                }
            } catch (e: UnsupportedException) {
                endResult.evaluationDetails = this.createEvaluationDetails(EvaluationReason.UNSUPPORTED)
                return
            }
        }

        endResult.booleanValue = pass
        endResult.jsonValue = rule.returnValue
        endResult.ruleID = rule.id
        endResult.groupName = rule.groupName
        endResult.isExperimentGroup = rule.isExperimentGroup == true
    }

    private fun conditionFromString(input: String?): ConfigCondition {
        return when (input) {
            Const.PUBLIC -> ConfigCondition.PUBLIC
            Const.FAIL_GATE -> ConfigCondition.FAIL_GATE
            Const.PASS_GATE -> ConfigCondition.PASS_GATE
            Const.IP_BASED -> ConfigCondition.IP_BASED
            Const.UA_BASED -> ConfigCondition.UA_BASED
            Const.USER_FIELD -> ConfigCondition.USER_FIELD
            Const.CURRENT_TIME -> ConfigCondition.CURRENT_TIME
            Const.ENVIRONMENT_FIELD -> ConfigCondition.ENVIRONMENT_FIELD
            Const.USER_BUCKET -> ConfigCondition.USER_BUCKET
            Const.UNIT_ID -> ConfigCondition.UNIT_ID
            else -> ConfigCondition.valueOf((input ?: Const.EMPTY_STR).uppercase())
        }
    }

    private fun evaluateCondition(user: StatsigUser, condition: APICondition, endResult: ConfigEvaluation): Boolean {
        try {
            var value: Any? = null
            val field: String = Utils.toStringOrEmpty(condition.field)
            val conditionEnum: ConfigCondition? = try {
                conditionFromString(condition.type.lowercase())
            } catch (e: java.lang.IllegalArgumentException) {
                errorBoundary.logException("evaluateCondition:condition", e)
                options.customLogger.warning("[Statsig]: An exception was caught:  $e")
                null
            }

            when (conditionEnum) {
                ConfigCondition.PUBLIC ->
                    return true

                ConfigCondition.FAIL_GATE, ConfigCondition.PASS_GATE -> {
                    val name = Utils.toStringOrEmpty(condition.targetValue)
                    this.checkGate(user, name, endResult)
                    val newExposure =
                        mapOf(
                            "gate" to name,
                            "gateValue" to endResult.booleanValue.toString(),
                            "ruleID" to endResult.ruleID,
                        )
                    endResult.secondaryExposures.add(newExposure)
                    val booleanVal = if (conditionEnum == ConfigCondition.PASS_GATE) endResult.booleanValue else !endResult.booleanValue
                    return booleanVal
                }

                ConfigCondition.IP_BASED -> {
                    value = getFromUser(user, field)
                    if (value == null) {
                        val ipString = getFromUser(user, "ip")?.toString()
                        value = if (ipString == null) {
                            null
                        } else {
                            CountryLookup.lookupIPString(ipString)
                        }
                    }
                }

                ConfigCondition.UA_BASED -> {
                    value = getFromUser(user, field)
                    if (value == null) {
                        value = getFromUserAgent(user, field)
                    }
                }

                ConfigCondition.USER_FIELD -> {
                    value = getFromUser(user, field)
                }

                ConfigCondition.CURRENT_TIME -> {
                    value = System.currentTimeMillis().toString()
                }

                ConfigCondition.ENVIRONMENT_FIELD -> {
                    value = getFromEnvironment(user, field)
                }

                ConfigCondition.USER_BUCKET -> {
                    val salt = getValueAsString(condition.additionalValues?.let { it["salt"] })
                    val unitID = getUnitID(user, condition.idType) ?: Const.EMPTY_STR
                    value = computeUserHash("$salt.$unitID").mod(1000UL)
                }

                ConfigCondition.UNIT_ID -> {
                    value = getUnitID(user, condition.idType)
                }

                else -> {
                    throw UnsupportedException("Unsupported Condition.")
                }
            }

            when (condition.operator) {
                Const.GT -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return false
                    }
                    return doubleValue > doubleTargetValue
                }

                Const.GTE -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return false
                    }
                    return doubleValue >= doubleTargetValue
                }

                Const.LT -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return false
                    }
                    return doubleValue < doubleTargetValue
                }

                Const.LTE -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return false
                    }
                    return doubleValue <= doubleTargetValue
                }

                Const.VERSION_GT -> {
                    return versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                        versionCompare(v1, v2) > 0
                    }
                }

                Const.VERSION_GTE -> {
                    return versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                        versionCompare(v1, v2) >= 0
                    }
                }

                Const.VERSION_LT -> {
                    return versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                        versionCompare(v1, v2) < 0
                    }
                }

                Const.VERSION_LTE -> {
                    return versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                        versionCompare(v1, v2) <= 0
                    }
                }

                Const.VERSION_EQ -> {
                    return versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                        versionCompare(v1, v2) == 0
                    }
                }

                Const.VERSION_NEQ -> {
                    return versionCompareHelper(value, condition.targetValue) { v1: String, v2: String ->
                        versionCompare(v1, v2) != 0
                    }
                }

                Const.ANY -> {
                    return matchStringInArray(value, condition.targetValue) { a, b ->
                        a.equals(b, true)
                    }
                }

                Const.NONE -> {
                    return !matchStringInArray(value, condition.targetValue) { a, b ->
                        a.equals(b, true)
                    }
                }

                Const.ANY_CASE_SENSITIVE -> {
                    return matchStringInArray(value, condition.targetValue) { a, b ->
                        a.equals(b, false)
                    }
                }

                Const.NONE_CASE_SENSITIVE -> {
                    return !matchStringInArray(value, condition.targetValue) { a, b ->
                        a.equals(b, false)
                    }
                }

                Const.STR_STARTS_WITH_ANY -> {
                    return matchStringInArray(value, condition.targetValue) { a, b ->
                        a.startsWith(b, true)
                    }
                }

                Const.STR_ENDS_WITH_ANY -> {
                    return matchStringInArray(value, condition.targetValue) { a, b ->
                        a.endsWith(b, true)
                    }
                }

                Const.STR_CONTAINS_ANY -> {
                    return matchStringInArray(value, condition.targetValue) { a, b ->
                        a.contains(b, true)
                    }
                }

                Const.STR_CONTAINS_NONE -> {
                    return !matchStringInArray(value, condition.targetValue) { a, b ->
                        a.contains(b, true)
                    }
                }

                Const.STR_MATCHES -> {
                    val targetValue = getValueAsString(condition.targetValue) ?: return false
                    val strValue =
                        getValueAsString(value) ?: return false

                    return Regex(targetValue).containsMatchIn(strValue)
                }

                Const.EQ -> {
                    return value == condition.targetValue
                }

                Const.NEQ -> {
                    return value != condition.targetValue
                }

                Const.BEFORE -> {
                    return compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.before(b)
                        },
                        value,
                        condition.targetValue,
                    )
                }

                Const.AFTER -> {
                    return compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.after(b)
                        },
                        value,
                        condition.targetValue,
                    )
                }

                Const.ON -> {
                    return compareDates(
                        { a: Date, b: Date ->
                            calendarOne.time = a
                            calendarTwo.time = b
                            return@compareDates calendarOne[Calendar.YEAR] ==
                                calendarTwo[Calendar.YEAR] &&
                                calendarOne[Calendar.DAY_OF_YEAR] ==
                                calendarTwo[Calendar.DAY_OF_YEAR]
                        },
                        value,
                        condition.targetValue,
                    )
                }

                Const.IN_SEGMENT_LIST, Const.NOT_IN_SEGMENT_LIST -> {
                    val idList = specStore.getIDList(Utils.toStringOrEmpty(condition.targetValue))
                    val stringValue = getValueAsString(value)
                    if (idList != null && stringValue != null) {
                        val bytes =
                            MessageDigest.getInstance(Const.CML_SHA_256)
                                .digest(stringValue.toByteArray())
                        val base64 = Base64.getEncoder().encodeToString(bytes)
                        val containsID = idList.contains(base64.substring(0, 8))
                        var booleanVal = !containsID
                        if (condition.operator == "in_segment_list") {
                            booleanVal = containsID
                        }
                        return booleanVal
                    }
                    return false
                }

                else -> {
                    throw UnsupportedException("Unsupported Condition.")
                }
            }
        } catch (e: IllegalArgumentException) {
            errorBoundary.logException("evaluateCondition:all", e)
        }
        return false
    }

    private fun matchStringInArray(
        value: Any?,
        target: Any?,
        compare: (value: String, target: String) -> Boolean,
    ): Boolean {
        val strValue = getValueAsString(value) ?: return false
        val iterable =
            when (target) {
                is Iterable<*> -> {
                    target
                }

                is Array<*> -> {
                    target.asIterable()
                }

                else -> {
                    return false
                }
            }

        for (match in iterable) {
            val strMatch = getValueAsString(match) ?: continue
            if (compare(strValue, strMatch)) {
                return true
            }
        }
        return false
    }

    private fun compareDates(
        compare: ((a: Date, b: Date) -> Boolean),
        a: Any?,
        b: Any?,
    ): Boolean {
        if (a == null || b == null) {
            return false
        }

        val firstEpoch = getDate(a)
        val secondEpoch = getDate(b)

        if (firstEpoch == null || secondEpoch == null) {
            return false
        }
        return compare(firstEpoch, secondEpoch)
    }

    private fun getEpoch(input: Any?): Long? {
        var epoch =
            when (input) {
                is String -> parseLong(input)
                is Number -> input.toLong()
                else -> return null
            }

        if (epoch.toString().length < 11) {
            // epoch in seconds (milliseconds would be before 1970)
            epoch *= 1000
        }

        return epoch
    }

    private fun parseISOTimestamp(input: Any?): Date? {
        if (input is String) {
            return try {
                val ta = DateTimeFormatter.ISO_INSTANT.parse(input)
                val i = Instant.from(ta)
                Date.from(i)
            } catch (e: Exception) {
                errorBoundary.logException("getDate", e)
                null
            }
        }
        return null
    }

    private fun getDate(input: Any?): Date? {
        if (input == null) {
            return null
        }
        return try {
            val epoch: Long = getEpoch(input) ?: return parseISOTimestamp(input)
            val instant = Instant.ofEpochMilli(epoch)
            Date.from(instant)
        } catch (e: Exception) {
            parseISOTimestamp(input)
        }
    }

    private fun versionCompare(v1: String, v2: String): Int {
        val parts1 = v1.split(".")
        val parts2 = v2.split(".")

        var i = 0
        while (i < parts1.size.coerceAtLeast(parts2.size)) {
            var c1 = 0
            var c2 = 0
            if (i < parts1.size) {
                c1 = parts1[i].toInt()
            }
            if (i < parts2.size) {
                c2 = parts2[i].toInt()
            }
            if (c1 < c2) {
                return -1
            } else if (c1 > c2) {
                return 1
            }
            i++
        }
        return 0
    }

    private fun versionCompareHelper(
        version1: Any?,
        version2: Any?,
        compare: (v1: String, v2: String) -> Boolean,
    ): Boolean {
        var version1Str = getValueAsString(version1)
        var version2Str = getValueAsString(version2)

        if (version1Str == null || version2Str == null) {
            return false
        }

        val dashIndex1 = version1Str.indexOf('-')
        if (dashIndex1 > 0) {
            version1Str = version1Str.substring(0, dashIndex1)
        }

        val dashIndex2 = version2Str.indexOf('-')
        if (dashIndex2 > 0) {
            version2Str = version2Str.substring(0, dashIndex2)
        }

        return try {
            compare(version1Str, version2Str)
        } catch (e: NumberFormatException) {
            false
        } catch (e: Exception) {
            errorBoundary.logException("versionCompareHelper", e)
            options.customLogger.warning("[Statsig]: An exception was caught:  $e")
            false
        }
    }

    private fun getValueAsString(input: Any?): String? {
        if (input == null) {
            return null
        }
        if (input is String) {
            return input
        }
        return input.toString()
    }

    private fun getValueAsDouble(input: Any?): Double? {
        if (input == null) {
            return null
        }

        if (input is String) {
            return input.toDoubleOrNull()
        }

        if (input is ULong) {
            return input.toDouble()
        }

        if (input is Double) {
            return input
        }

        if (input is Number) {
            return input.toDouble()
        }

        return null
    }

    private fun contains(targets: Any?, value: Any?, ignoreCase: Boolean): Boolean {
        if (targets == null || value == null) {
            return false
        }
        val iterable: Iterable<*> = when (targets) {
            is Iterable<*> -> {
                targets
            }

            is Array<*> -> {
                targets.asIterable()
            }

            else -> {
                return false
            }
        }
        for (option in iterable) {
            if ((option is String) && (value is String) && option.equals(value, ignoreCase)) {
                return true
            }
            if (option == value) {
                return true
            }
        }
        return false
    }

    private fun getFromUserAgent(user: StatsigUser, field: String): String? {
        val ua = getFromUser(user, "userAgent")?.toString() ?: return null
        return when (field.lowercase()) {
            Const.OS_NAME, Const.OSNAME -> osFamilyFromUserAgent(ua)
            Const.OS_VERSION, Const.OSVERSION -> osVersionFromUserAgent(ua)
            Const.BROWSER_NAME, Const.BROWSERNAME -> userAgentFamilyFromUserAgent(ua)
            Const.BROWSER_VERSION, Const.BROWSERVERSION -> browserVersionFromUserAgent(ua)
            else -> {
                null
            }
        }
    }

    private fun osFamilyFromUserAgent(userAgent: String): String {
        val os = uaParser.parseOS(userAgent)
        return os.family
    }

    private fun osVersionFromUserAgent(userAgent: String): String {
        val os = uaParser.parseOS(userAgent)
        return arrayOf(
            if (os.major.isNullOrBlank()) Const.ZERO else os.major,
            if (os.minor.isNullOrBlank()) Const.ZERO else os.minor,
            if (os.patch.isNullOrBlank()) Const.ZERO else os.patch,
        ).joinToString(".")
    }

    private fun userAgentFamilyFromUserAgent(userAgent: String): String {
        val agent = uaParser.parseUserAgent(userAgent)
        return agent.family
    }

    private fun browserVersionFromUserAgent(userAgent: String): String {
        val agent = uaParser.parseUserAgent(userAgent)
        return arrayOf(
            if (agent.major.isNullOrBlank()) "0" else agent.major,
            if (agent.minor.isNullOrBlank()) "0" else agent.minor,
            if (agent.patch.isNullOrBlank()) "0" else agent.patch,
        ).joinToString(".")
    }

    private fun getUserValueForField(user: StatsigUser, field: String): Any? {
        return when (field) {
            Const.USERID, Const.USER_ID -> user.userID
            Const.EMAIL -> user.email
            Const.IP, Const.IPADDRESS, Const.IP_ADDRESS -> user.ip
            Const.USERAGENT, Const.USER_AGENT -> user.userAgent
            Const.COUNTRY -> user.country
            Const.LOCALE -> user.locale
            Const.APPVERSION, Const.APP_VERSION -> user.appVersion
            else -> null
        }
    }

    private fun getFromUser(user: StatsigUser, field: String): Any? {
        var value: Any? = getUserValueForField(user, field) ?: getUserValueForField(user, field.lowercase())

        if ((value == null || value == Const.EMPTY_STR) && user.custom != null) {
            value = user.custom?.get(field) ?: user.custom?.get(field.lowercase())
        }
        if ((value == null || value == Const.EMPTY_STR) && user.privateAttributes != null) {
            value =
                user.privateAttributes?.get(field)
                    ?: user.privateAttributes?.get(field.lowercase())
        }

        return value
    }

    private fun getFromEnvironment(user: StatsigUser, field: String): String? {
        return user.statsigEnvironment?.get(field)
            ?: user.statsigEnvironment?.get(field.lowercase())
    }

    private fun computeUserHash(input: String): ULong {
        hashLookupTable[input]?.let {
            return it
        }

        val md = MessageDigest.getInstance(Const.CML_SHA_256)
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        val hash = ByteBuffer.wrap(bytes).long.toULong()

        if (hashLookupTable.size > 1000) {
            hashLookupTable.clear()
        }

        hashLookupTable[input] = hash
        return hash
    }

    private fun getUnitID(user: StatsigUser, idType: String?): String? {
        val lowerIdType = idType?.lowercase()
        if (lowerIdType != "userid" && lowerIdType?.isEmpty() == false) {
            return user.customIDs?.get(idType) ?: user.customIDs?.get(lowerIdType)
        }
        return user.userID
    }
}

internal enum class ConfigCondition {
    PUBLIC,
    FAIL_GATE,
    PASS_GATE,
    IP_BASED,
    UA_BASED,
    USER_FIELD,
    CURRENT_TIME,
    ENVIRONMENT_FIELD,
    USER_BUCKET,
    UNIT_ID,
}
