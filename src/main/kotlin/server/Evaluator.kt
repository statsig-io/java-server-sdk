package server

import com.blueconic.browscap.BrowsCapField
import com.blueconic.browscap.UserAgentParser
import com.blueconic.browscap.UserAgentService
import com.google.gson.Gson
import org.apache.maven.artifact.versioning.ComparableVersion
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.collections.set

data class ConfigEvaluation(
    val fetchFromServer: Boolean = false,
    val booleanValue: Boolean = false,
    val jsonValue: Any? = null,
    val ruleID: String? = null,
)

class Evaluator {
    private var featureGates: MutableMap<String, APIConfig> = HashMap()
    private var dynamicConfigs: MutableMap<String, APIConfig> = HashMap()
    private var uaParser: UserAgentParser? = try {
        UserAgentService().loadParser(
            listOf(
                BrowsCapField.BROWSER,
                BrowsCapField.BROWSER_VERSION,
                BrowsCapField.BROWSER_MAJOR_VERSION,
                BrowsCapField.BROWSER_MINOR_VERSION,
                BrowsCapField.PLATFORM,
                BrowsCapField.PLATFORM_VERSION
            )
        )
    } catch (e: Exception) {
        null
    }

    fun setDownloadedConfigs(downloadedConfig: APIDownloadedConfigs) {
        for (config in downloadedConfig.featureGates) {
            featureGates[config.name] = config
        }
        for (config in downloadedConfig.dynamicConfigs) {
            dynamicConfigs[config.name] = config
        }
    }

    fun getConfig(user: StatsigUser, dynamicConfigName: String): ConfigEvaluation {
        if (!dynamicConfigs.containsKey(dynamicConfigName)) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false, mapOf<String, Any>())
        }
        val config = dynamicConfigs[dynamicConfigName]
            ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false, mapOf<String, Any>())
        return this.evaluate(user, config)
    }

    fun checkGate(user: StatsigUser, gateName: String): ConfigEvaluation {
        if (!featureGates.containsKey(gateName)) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        }
        val config = featureGates[gateName] ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        return this.evaluate(user, config)
    }

    private fun evaluate(user: StatsigUser, config: APIConfig): ConfigEvaluation {
        if (!config.enabled) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false, config.defaultValue)
        }
        for (rule in config.rules) {
            val result = this.evaluateRule(user, rule)
            if (result.fetchFromServer) {
                return result
            }
            if (result.booleanValue) {
                val pass = computeUserHashBucket(config.salt + '.' + rule.id + '.' + user.userID) < rule.passPercentage.toULong().times(100UL)
                return ConfigEvaluation(false, pass, config.defaultValue, rule.id)
            }
        }
        return ConfigEvaluation(fetchFromServer = false, booleanValue = false, config.defaultValue, "default")
    }

    private fun evaluateRule(user: StatsigUser, rule: APIRule): ConfigEvaluation {
        for (condition in rule.conditions) {
            val result = this.evaluateCondition(user, condition)
            if (result.fetchFromServer) {
                return result
            }
            if (!result.booleanValue) {
                return ConfigEvaluation(fetchFromServer = false, booleanValue = false, rule.returnValue, rule.id)
            }
        }
        return ConfigEvaluation(fetchFromServer = false, booleanValue = true, rule.returnValue, rule.id)
    }

    private fun evaluateCondition(user: StatsigUser, condition: APICondition): ConfigEvaluation {
        try {
            var value: Any?
            var conditionEnum: ConfigCondition? = null
            try {
                if (!condition.type.isNullOrEmpty()) {
                    conditionEnum = ConfigCondition.valueOf(condition.type.uppercase())
                }
            } catch (_E: java.lang.IllegalArgumentException) {
                conditionEnum = null
            }
            when (conditionEnum) {
                ConfigCondition.PUBLIC -> return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                ConfigCondition.FAIL_GATE -> {
                    val result = this.checkGate(user, condition.targetValue as String)
                    return ConfigEvaluation(result.fetchFromServer, !result.booleanValue, result.jsonValue)
                }
                ConfigCondition.PASS_GATE -> {
                    return checkGate(user, condition.targetValue as String)
                }
                ConfigCondition.IP_BASED -> {
                    value = getFromUser(user, condition.field)
                    if (value == null) {
                        if (getFromUser(user, "ip") == null) {
                            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                        } else {
                            return ConfigEvaluation(fetchFromServer = true)
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
                    val userID = user.userID
                    value = computeUserHashBucket("$salt.$userID").toDouble()
                }
                else -> {
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }
            if (value == null) {
                return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
            }
            when (condition.operator) {
                "gt" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(fetchFromServer = false, doubleValue > doubleTargetValue)
                }
                "gte" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(fetchFromServer = false, doubleValue >= doubleTargetValue)
                }
                "lt" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(fetchFromServer = false, doubleValue < doubleTargetValue)
                }
                "lte" -> {
                    val doubleValue = getValueAsDouble(value)
                    val doubleTargetValue = getValueAsDouble(condition.targetValue)
                    if (doubleValue == null || doubleTargetValue == null) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    return ConfigEvaluation(fetchFromServer = false, doubleValue <= doubleTargetValue)
                }

                "version_gt" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    var sourceVersion = ComparableVersion(strValue)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                        false,
                        sourceVersion.compareTo(targetVersion) > 0
                    )
                }
                "version_gte" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    var sourceVersion = ComparableVersion(strValue)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                        false,
                        sourceVersion.compareTo(targetVersion) >= 0
                    )
                }
                "version_lt" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    var sourceVersion = ComparableVersion(strValue)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        sourceVersion.compareTo(targetVersion) < 0
                    )
                }
                "version_lte" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    var sourceVersion = ComparableVersion(strValue)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        sourceVersion.compareTo(targetVersion) <= 0
                    )
                }
                "version_eq" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    var sourceVersion = ComparableVersion(strValue)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        sourceVersion.compareTo(targetVersion) == 0
                    )
                }
                "version_neq" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    var sourceVersion = ComparableVersion(strValue)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        sourceVersion.compareTo(targetVersion) != 0
                    )
                }

                "any" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        containsCaseInsensitive(condition.targetValue, getValueAsString(value))
                    )
                }
                "none" -> {
                    return ConfigEvaluation(
                        fetchFromServer = false,
                        !containsCaseInsensitive(condition.targetValue, getValueAsString(value))
                    )
                }

                "str_starts_with_any" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    if (condition.targetValue is Iterable<*>) {
                        for (match in condition.targetValue) {
                            if (strValue.startsWith(match as String)) {
                                return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                            }
                        }
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    val singleTarget = (condition.targetValue as String)
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = strValue.startsWith(singleTarget))
                }
                "str_ends_with_any" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    if (condition.targetValue is Iterable<*>) {
                        for (match in condition.targetValue) {
                            if (strValue.endsWith(match as String)) {
                                return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                            }
                        }
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    val singleTarget = (condition.targetValue as String)
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = strValue.endsWith(singleTarget))
                }
                "str_contains_any" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    if (condition.targetValue is Iterable<*>) {
                        for (match in condition.targetValue) {
                            if (strValue.contains(match as String)) {
                                return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                            }
                        }
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    }
                    val singleTarget = (condition.targetValue as String)
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = strValue.contains(singleTarget))
                }
                "str_matches" -> {
                    val strValue = getValueAsString(value)
                        ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                    if (strValue.matches(Regex(condition.targetValue as String))) {
                        return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                    }
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                }

                "eq" -> {
                    return ConfigEvaluation(fetchFromServer = false, value == condition.targetValue)
                }
                "neq" -> {
                    return ConfigEvaluation(fetchFromServer = false, value != condition.targetValue)
                }

                // TODO dates

                else -> {
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }
        } catch (_e: IllegalArgumentException) {
            return ConfigEvaluation(true)
        }
    }

    private fun getValueAsString(input: Any?): String? {
        if (input == null) {
            return null
        }
        if (input is String) {
            return input
        }
        if (input is Number) {
            return input.toString()
        }
        return input as? String
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

    private fun containsCaseInsensitive(targets: Any, value: String?): Boolean {
        if (value == null) {
            return false
        }
        if (targets is String) {
            return targets.lowercase() == value.lowercase()
        }
        if (targets is Iterable<*>) {
            for (option in targets) {
                if (option is String && option.lowercase() == value.lowercase()) {
                    return true
                }
            }
        }

        return false
    }

    private fun getFromUserAgent(user: StatsigUser, field: String): String? {
        val ua = getFromUser(user, "userAgent") ?: return null
        val parsed = uaParser?.parse(ua) ?: return null
        when (field) {
            "os_name" -> {
                if (parsed.platform.lowercase().startsWith("win")) {
                    return "Windows"
                }
                return parsed.platform
            }
            "os_version" -> return parsed.platformVersion
            "browser_name" -> return parsed.browser
            "browser_version" -> return parsed.browserMajorVersion
            else -> {
                return null
            }
        }
    }

    private fun getFromUser(user: StatsigUser, field: String): String? {
        val userJson = Gson().toJsonTree(user).asJsonObject
        if (userJson[field] != null) {
            return userJson[field].asString
        } else if (userJson["custom"] != null) {
            return Gson().toJsonTree(userJson["custom"]).asJsonObject[field]?.asString
        } else {
            return null
        }
    }

    private fun getFromEnvironment(user: StatsigUser, field: String): String? {
        if (user.statsigEnvironment == null) {
            return null
        }
        if (user.statsigEnvironment!![field] != null) {
            return user.statsigEnvironment!![field]
        } else if (user.statsigEnvironment!![field.lowercase()] != null) {
            return user.statsigEnvironment!![field.lowercase()]
        }
        return null
    }

    private fun computeUserHashBucket(input: String): ULong {
        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        val hash = ByteBuffer.wrap(bytes).long.toULong()
        return hash.mod(10000UL)
    }
}

enum class ConfigCondition {
    PUBLIC,
    FAIL_GATE,
    PASS_GATE,
    IP_BASED,
    UA_BASED,
    USER_FIELD,
    CURRENT_TIME,
    ENVIRONMENT_FIELD,
    USER_BUCKET,
}
