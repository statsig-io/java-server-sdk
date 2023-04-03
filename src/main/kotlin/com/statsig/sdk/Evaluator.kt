package com.statsig.sdk

import ip3country.CountryLookup
import kotlinx.coroutines.CoroutineScope
import ua_parser.Client
import ua_parser.Parser
import java.lang.Long.parseLong
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Calendar
import java.util.Date
import kotlin.collections.set

internal data class ConfigEvaluation(
    val fetchFromServer: Boolean = false,
    val booleanValue: Boolean = false,
    val jsonValue: Any? = null,
    val ruleID: String = "",
    val groupName: String? = null,
    val secondaryExposures: ArrayList<Map<String, String>> = arrayListOf(),
    val explicitParameters: Array<String> = arrayOf(),
    val configDelegate: String? = null,
    var evaluationDetails: EvaluationDetails? = null,
    var isExperimentGroup: Boolean = false,
) {
    var undelegatedSecondaryExposures: ArrayList<Map<String, String>> = secondaryExposures
}

internal class Evaluator(
    private var network: StatsigNetwork,
    private var options: StatsigOptions,
    private val statsigScope: CoroutineScope,
    private val errorBoundary: ErrorBoundary
) {
    private var specStore: SpecStore
    private var uaParser: Parser = Parser()
    private var gateOverrides: MutableMap<String, Boolean> = HashMap()
    private var configOverrides: MutableMap<String, Map<String, Any>> = HashMap()
    private var layerOverrides: MutableMap<String, Map<String, Any>> = HashMap()

    var isInitialized: Boolean = false
    init {
        CountryLookup.initialize()
        specStore = SpecStore(this.network, this.options, StatsigMetadata(), statsigScope, errorBoundary)
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
        var variants: MutableMap<String, Map<String, Any>> = HashMap()
        val config = specStore.getConfig(configName) ?: return variants

        var previousAllocation = 0.0
        for (r: APIRule in config!!.rules) {
            val value = r.returnValue.toString()
            val cond = r.conditions[0]
            var percent = 0.0
            if (cond.type.lowercase() == "user_bucket" && cond.targetValue is Number) {
                percent = (cond.targetValue.toDouble() - previousAllocation) / 1000.0
                previousAllocation = cond.targetValue.toDouble()
            }
            variants[r.groupName] = mapOf("value" to value, "percent" to percent)
        }
        return variants
    }

    // check if a user is overridden to any group for the experiment
    fun isUserOverriddenToExperiment(user: StatsigUser, expName: String): Boolean {
        val config = specStore.getConfig(expName) ?: return false
        for (rule in config.rules) {
            if (rule.id.contains("override", ignoreCase = true)) {
                val result = evaluateRule(user, rule)
                if (result.booleanValue) {
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
                val result = evaluateRule(user, rule)
                // user is in an experiment when they FAIL the layerAssignment rule
                return !result.booleanValue
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

    fun getConfig(user: StatsigUser, dynamicConfigName: String): ConfigEvaluation {
        if (configOverrides.containsKey(dynamicConfigName)) {
            return ConfigEvaluation(
                jsonValue = configOverrides[dynamicConfigName] ?: mapOf<String, Any>(),
                evaluationDetails = this.createEvaluationDetails(EvaluationReason.LOCAL_OVERRIDE)
            )
        }

        if (specStore.getEvaluationReason() == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED)
            )
        }

        return this.evaluateConfig(user, specStore.getConfig(dynamicConfigName))
    }

    fun getClientInitializeResponse(user: StatsigUser): Map<String, Any> {
        return ClientInitializeFormatter(this.specStore, this::evaluateConfig, user).getFormattedResponse()
    }

    fun getLayer(user: StatsigUser, layerName: String): ConfigEvaluation {
        if (layerOverrides.containsKey(layerName)) {
            val value = layerOverrides[layerName] ?: mapOf()
            return ConfigEvaluation(
                jsonValue = value,
                evaluationDetails = this.createEvaluationDetails(EvaluationReason.LOCAL_OVERRIDE)
            )
        }

        if (specStore.getEvaluationReason() == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED)
            )
        }

        return this.evaluateConfig(user, specStore.getLayerConfig(layerName))
    }

    fun getExperimentsInLayer(layerName: String): Array<String> {
        return specStore.getLayer(layerName) ?: emptyArray()
    }

    fun checkGate(user: StatsigUser, gateName: String): ConfigEvaluation {
        if (gateOverrides.containsKey(gateName)) {
            val value = gateOverrides[gateName] ?: false
            return ConfigEvaluation(
                booleanValue = value,
                jsonValue = value,
                evaluationDetails = createEvaluationDetails(EvaluationReason.LOCAL_OVERRIDE)
            )
        }

        if (specStore.getEvaluationReason() == EvaluationReason.UNINITIALIZED) {
            return ConfigEvaluation(
                evaluationDetails = createEvaluationDetails(EvaluationReason.UNINITIALIZED)
            )
        }

        val evalGate = specStore.getGate(gateName)
        return this.evaluateConfig(user, evalGate)
    }

    private fun evaluateConfig(user: StatsigUser, config: APIConfig?): ConfigEvaluation {
        val unwrappedConfig =
            config
                ?: return ConfigEvaluation(
                    fetchFromServer = false,
                    booleanValue = false,
                    mapOf<String, Any>(),
                    evaluationDetails = createEvaluationDetails(EvaluationReason.UNRECOGNIZED)
                )
        return this.evaluate(user, unwrappedConfig)
    }

    private fun evaluate(user: StatsigUser, config: APIConfig): ConfigEvaluation {
        val evaluationDetails = createEvaluationDetails(specStore.getEvaluationReason())
        if (!config.enabled) {
            return ConfigEvaluation(
                fetchFromServer = false,
                booleanValue = false,
                config.defaultValue,
                "disabled",
                evaluationDetails = evaluationDetails
            )
        }
        val secondaryExposures = arrayListOf<Map<String, String>>()
        for (rule in config.rules) {
            val result = this.evaluateRule(user, rule)
            result.evaluationDetails = evaluationDetails

            if (result.fetchFromServer) {
                return result
            }

            secondaryExposures.addAll(result.secondaryExposures)
            if (result.booleanValue) {
                this.evaluateDelegate(user, rule, secondaryExposures)?.let {
                    return it
                }

                val pass =
                    computeUserHash(
                        config.salt +
                            '.' +
                            (rule.salt ?: rule.id) +
                            '.' +
                            (getUnitID(user, rule.idType) ?: "")
                    )
                        .mod(10000UL) < (rule.passPercentage.times(100.0)).toULong()

                return ConfigEvaluation(
                    false,
                    pass,
                    if (pass) result.jsonValue else config.defaultValue,
                    result.ruleID,
                    result.groupName,
                    secondaryExposures,
                    evaluationDetails = evaluationDetails,
                    isExperimentGroup = rule.isExperimentGroup ?: false
                )
            }
        }
        return ConfigEvaluation(
            fetchFromServer = false,
            booleanValue = false,
            config.defaultValue,
            "default",
            "",
            secondaryExposures,
            evaluationDetails = evaluationDetails
        )
    }

    private fun evaluateDelegate(
        user: StatsigUser,
        rule: APIRule,
        secondaryExposures: ArrayList<Map<String, String>>
    ): ConfigEvaluation? {
        val configDelegate = rule.configDelegate ?: return null
        val config = specStore.getConfig(configDelegate) ?: return null

        val delegatedResult = this.evaluate(user, config)
        val undelegatedSecondaryExposures = arrayListOf<Map<String, String>>()
        undelegatedSecondaryExposures.addAll(secondaryExposures)
        secondaryExposures.addAll(delegatedResult.secondaryExposures)

        var evaluation = ConfigEvaluation(
            fetchFromServer = delegatedResult.fetchFromServer,
            booleanValue = delegatedResult.booleanValue,
            jsonValue = delegatedResult.jsonValue,
            ruleID = delegatedResult.ruleID,
            groupName = delegatedResult.groupName,
            secondaryExposures = secondaryExposures,
            configDelegate = rule.configDelegate,
            explicitParameters = config.explicitParameters ?: arrayOf(),
            evaluationDetails = this.createEvaluationDetails(this.specStore.getEvaluationReason())
        )
        evaluation.undelegatedSecondaryExposures = undelegatedSecondaryExposures
        return evaluation
    }

    private fun evaluateRule(user: StatsigUser, rule: APIRule): ConfigEvaluation {
        val secondaryExposures = arrayListOf<Map<String, String>>()
        var pass = true
        for (condition in rule.conditions) {
            val result = this.evaluateCondition(user, condition)
            if (result.fetchFromServer) {
                return result
            }
            if (!result.booleanValue) {
                pass = false
            }
            secondaryExposures.addAll(result.secondaryExposures)
        }

        return ConfigEvaluation(
            fetchFromServer = false,
            booleanValue = pass,
            rule.returnValue,
            rule.id,
            rule.groupName,
            secondaryExposures,
            isExperimentGroup = rule.isExperimentGroup == true
        )
    }

    private fun evaluateCondition(user: StatsigUser, condition: APICondition): ConfigEvaluation {
        try {
            var value: Any?
            var conditionEnum: ConfigCondition? = null
            try {
                if (!condition.type.isNullOrEmpty()) {
                    conditionEnum = ConfigCondition.valueOf(condition.type.uppercase())
                }
            } catch (e: java.lang.IllegalArgumentException) {
                errorBoundary.logException("evaluateCondition:condition", e)
                println("[Statsig]: An exception was caught:  $e")
                conditionEnum = null
            }
            when (conditionEnum) {
                ConfigCondition.PUBLIC ->
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                ConfigCondition.FAIL_GATE, ConfigCondition.PASS_GATE -> {
                    val result = this.checkGate(user, condition.targetValue as String)
                    val newExposure =
                        mapOf(
                            "gate" to condition.targetValue,
                            "gateValue" to result.booleanValue.toString(),
                            "ruleID" to result.ruleID,
                        )
                    val secondaryExposures = arrayListOf<Map<String, String>>()
                    secondaryExposures.addAll(result.secondaryExposures)
                    secondaryExposures.add(newExposure)
                    return ConfigEvaluation(
                        result.fetchFromServer,
                        if (conditionEnum == ConfigCondition.PASS_GATE) result.booleanValue
                        else !result.booleanValue,
                        result.jsonValue,
                        "",
                        "",
                        secondaryExposures
                    )
                }
                ConfigCondition.IP_BASED -> {
                    value = getFromUser(user, condition.field)
                    if (value == null) {
                        val ipString = getFromUser(user, "ip")?.toString()
                        if (ipString == null) {
                            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                        } else {
                            value = CountryLookup.lookupIPString(ipString)
                        }
                    }
                }
                ConfigCondition.UA_BASED -> {
                    value = getFromUser(user, condition.field)
                    if (value == null && !condition.field.equals("browser_version")) {
                        value = getFromUserAgent(user, condition.field)
                    }
                }
                ConfigCondition.USER_FIELD -> {
                    value = getFromUser(user, condition.field)
                }
                ConfigCondition.CURRENT_TIME -> {
                    value = System.currentTimeMillis().toString()
                }
                ConfigCondition.ENVIRONMENT_FIELD -> {
                    value = getFromEnvironment(user, condition.field)
                }
                ConfigCondition.USER_BUCKET -> {
                    val salt = getValueAsString(condition.additionalValues["salt"])
                    val unitID = getUnitID(user, condition.idType) ?: ""
                    value = computeUserHash("$salt.$unitID").mod(1000UL)
                }
                ConfigCondition.UNIT_ID -> {
                    value = getUnitID(user, condition.idType)
                }
                else -> {
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }

            when (condition.operator) {
                "gt" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue > doubleTargetValue
                    )
                }
                "gte" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue >= doubleTargetValue
                    )
                }
                "lt" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue < doubleTargetValue
                    )
                }
                "lte" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        doubleValue <= doubleTargetValue
                    )
                }
                "version_gt" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue as String) { v1: String,
                            v2: String ->
                            versionCompare(v1, v2) > 0
                        }
                    )
                }
                "version_gte" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue as String) { v1: String,
                            v2: String ->
                            versionCompare(v1, v2) >= 0
                        }
                    )
                }
                "version_lt" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue as String) { v1: String,
                            v2: String ->
                            versionCompare(v1, v2) < 0
                        }
                    )
                }
                "version_lte" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue as String) { v1: String,
                            v2: String ->
                            versionCompare(v1, v2) <= 0
                        }
                    )
                }
                "version_eq" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue as String) { v1: String,
                            v2: String ->
                            versionCompare(v1, v2) == 0
                        }
                    )
                }
                "version_neq" -> {
                    return ConfigEvaluation(
                        false,
                        versionCompareHelper(value, condition.targetValue as String) { v1: String,
                            v2: String ->
                            versionCompare(v1, v2) != 0
                        }
                    )
                }
                "any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }
                "none" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, true)
                        },
                    )
                }
                "any_case_sensitive" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }
                "none_case_sensitive" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !matchStringInArray(value, condition.targetValue) { a, b ->
                            a.equals(b, false)
                        },
                    )
                }
                "str_starts_with_any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        matchStringInArray(value, condition.targetValue) { a, b ->
                            a.startsWith(b, true)
                        },
                    )
                }
                "str_ends_with_any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        matchStringInArray(value, condition.targetValue) { a, b ->
                            a.endsWith(b, true)
                        },
                    )
                }
                "str_contains_any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }
                "str_contains_none" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !matchStringInArray(value, condition.targetValue) { a, b ->
                            a.contains(b, true)
                        },
                    )
                }
                "str_matches" -> {
                    val strValue =
                        getValueAsString(value)
                            ?: return ConfigEvaluation(
                                fetchFromServer = false,
                                booleanValue = false
                            )
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        booleanValue = Regex(condition.targetValue as String).containsMatchIn(strValue)
                    )
                }
                "eq" -> {
                    return ConfigEvaluation(fetchFromServer = false, value == condition.targetValue)
                }
                "neq" -> {
                    return ConfigEvaluation(fetchFromServer = false, value != condition.targetValue)
                }
                "before" -> {
                    return compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.before(b)
                        },
                        value,
                        condition.targetValue
                    )
                }
                "after" -> {
                    return compareDates(
                        { a: Date, b: Date ->
                            return@compareDates a.after(b)
                        },
                        value,
                        condition.targetValue
                    )
                }
                "on" -> {
                    return compareDates(
                        { a: Date, b: Date ->
                            val firstCalendar = Calendar.getInstance()
                            val secondCalendar = Calendar.getInstance()
                            firstCalendar.time = a
                            secondCalendar.time = b
                            return@compareDates firstCalendar[Calendar.YEAR] ==
                                secondCalendar[Calendar.YEAR] &&
                                firstCalendar[Calendar.DAY_OF_YEAR] ==
                                secondCalendar[Calendar.DAY_OF_YEAR]
                        },
                        value,
                        condition.targetValue
                    )
                }
                "in_segment_list", "not_in_segment_list" -> {
                    val idList = specStore.getIDList(condition.targetValue as String)
                    val stringValue = getValueAsString(value)
                    if (idList != null && stringValue != null) {
                        val bytes =
                            MessageDigest.getInstance("SHA-256")
                                .digest(stringValue.toByteArray())
                        val base64 = Base64.getEncoder().encodeToString(bytes)
                        val containsID = idList.contains(base64.substring(0, 8))
                        return ConfigEvaluation(
                            fetchFromServer = false,
                            if (condition.operator == "in_segment_list") containsID
                            else !containsID,
                        )
                    }
                    return ConfigEvaluation(fetchFromServer = false, false)
                }
                else -> {
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }
        } catch (e: IllegalArgumentException) {
            errorBoundary.logException("evaluateCondition:all", e)
            println("[Statsig]: An exception was caught:  $e")
            return ConfigEvaluation(true)
        }
    }

    private fun matchStringInArray(
        value: Any?,
        target: Any?,
        compare: (value: String, target: String) -> Boolean
    ): Boolean {
        var strValue = getValueAsString(value) ?: return false
        var iterable =
            if (target is Iterable<*>) {
                target
            } else if (target is Array<*>) {
                target.asIterable()
            } else {
                return false
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
        compare: (a: Date, b: Date) -> Boolean,
        a: Any?,
        b: Any?
    ): ConfigEvaluation {
        if (a == null || b == null) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        }
        val firstDate = getDate(a)
        val secondDate = getDate(b)
        if (firstDate == null || secondDate == null) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        }
        return ConfigEvaluation(
            fetchFromServer = false,
            booleanValue = compare(firstDate, secondDate)
        )
    }

    private fun getDate(input: Any?): Date? {
        if (input == null) {
            return null
        }
        return try {
            var epoch =
                if (input is String) {
                    parseLong(input)
                } else if (input is Number) {
                    input.toLong()
                } else {
                    return null
                }
            if (epoch.toString().length < 11) {
                // epoch in seconds (milliseconds would be before 1970)
                epoch *= 1000
            }
            Date(epoch)
        } catch (e: Exception) {
            try {
                val ta = DateTimeFormatter.ISO_INSTANT.parse(input as String)
                val i = Instant.from(ta)
                Date.from(i)
            } catch (e: Exception) {
                errorBoundary.logException("getDate", e)
                println("[Statsig]: An exception was caught:  $e")
                null
            }
        }
    }

    private fun versionCompare(v1: String, v2: String): Int {
        var parts1 = v1.split(".")
        var parts2 = v2.split(".")

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
        compare: (v1: String, v2: String) -> Boolean
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
        } catch (e: Exception) {
            errorBoundary.logException("versionCompareHelper", e)
            println("[Statsig]: An exception was caught:  $e")
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
        if (input is Number) {
            return input.toDouble()
        }
        if (input is ULong) {
            return input.toDouble()
        }
        return input as? Double
    }

    private fun contains(targets: Any?, value: Any?, ignoreCase: Boolean): Boolean {
        if (targets == null || value == null) {
            return false
        }
        var iterable: Iterable<*>
        if (targets is Iterable<*>) {
            iterable = targets
        } else if (targets is Array<*>) {
            iterable = targets.asIterable()
        } else {
            return false
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
        val ua = getFromUser(user, "userAgent") ?: return null
        val c: Client = uaParser.parse(ua.toString())
        when (field.lowercase()) {
            "os_name", "osname" -> return c.os.family
            "os_version", "osversion" ->
                return arrayOf(
                    if (c.os.major.isNullOrBlank()) "0" else c.os.major,
                    if (c.os.minor.isNullOrBlank()) "0" else c.os.minor,
                    if (c.os.patch.isNullOrBlank()) "0" else c.os.patch,
                )
                    .joinToString(".")
            "browser_name", "browsername" -> return c.userAgent.family
            "browser_version", "browserversion" ->
                return arrayOf(
                    if (c.userAgent.major.isNullOrBlank()) "0"
                    else c.userAgent.major,
                    if (c.userAgent.minor.isNullOrBlank()) "0"
                    else c.userAgent.minor,
                    if (c.userAgent.patch.isNullOrBlank()) "0"
                    else c.userAgent.patch,
                )
                    .joinToString(".")
            else -> {
                return null
            }
        }
    }

    private fun getFromUser(user: StatsigUser, field: String): Any? {
        var value: Any? = null
        when (field.lowercase()) {
            "userid", "user_id" -> value = user.userID
            "email" -> value = user.email
            "ip", "ipaddress", "ip_address" -> value = user.ip
            "useragent", "user_agent" -> value = user.userAgent
            "country" -> value = user.country
            "locale" -> value = user.locale
            "appversion", "app_version" -> value = user.appVersion
        }
        if ((value == null || value == "") && user.custom != null) {
            value = user.custom?.get(field) ?: user.custom?.get(field.lowercase())
        }
        if ((value == null || value == "") && user.privateAttributes != null) {
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
        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        return ByteBuffer.wrap(bytes).long.toULong()
    }

    private fun getUnitID(user: StatsigUser, idType: String?): String? {
        if (idType != null && !idType.isNullOrEmpty() && idType.lowercase() != "userid") {
            return user.customIDs?.get(idType) ?: user.customIDs?.get(idType.lowercase())
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
    UNIT_ID
}
