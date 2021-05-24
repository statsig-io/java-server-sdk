package server

import com.blueconic.browscap.BrowsCapField
import com.blueconic.browscap.UserAgentParser
import com.blueconic.browscap.UserAgentService
import com.google.gson.Gson
import org.apache.maven.artifact.versioning.ComparableVersion
import java.math.BigInteger
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

    fun getConfig(user: StatsigUser?, dynamicConfigName: String): ConfigEvaluation {
        if (!dynamicConfigs.containsKey(dynamicConfigName)) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        }
        val config = dynamicConfigs[dynamicConfigName]
            ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        return this.evaluate(user, config)
    }

    fun checkGate(user: StatsigUser?, gateName: String): ConfigEvaluation {
        if (!featureGates.containsKey(gateName)) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        }
        val config = featureGates[gateName] ?: return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
        return this.evaluate(user, config)
    }

    private fun evaluate(user: StatsigUser?, config: APIConfig): ConfigEvaluation {
        if (!config.enabled) {
            return ConfigEvaluation(fetchFromServer = false, booleanValue = false, config.defaultValue)
        }
        for (rule in config.rules) {
            val result = this.evaluateRule(user, rule)
            if (result.fetchFromServer) {
                return result
            }
            if (result.booleanValue) {
                val userID = user?.userID ?: ""
                val numericRepresentation = this.getHashedValue(config.salt + '.' + rule.name + '.' + userID)
                val pass = numericRepresentation.mod(BigInteger.valueOf(10000L)) < BigInteger.valueOf(rule.passPercentage * 100L)
                return ConfigEvaluation(false, pass, config.defaultValue, rule.id)
            }
        }
        return ConfigEvaluation(fetchFromServer = false, booleanValue = false, config.defaultValue, "default")
    }

    private fun evaluateRule(user: StatsigUser?, rule: APIRule): ConfigEvaluation {
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

    private fun evaluateCondition(user: StatsigUser?, condition: APICondition): ConfigEvaluation {
        try {
            var value: String?
            var conditionEnum: ConfigCondition? = null
            try {
                if (!condition.type.isNullOrEmpty()) {
                    conditionEnum = ConfigCondition.valueOf(condition.type?.toUpperCase())
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
                    return ConfigEvaluation(fetchFromServer = true)
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
                else -> {
                    return ConfigEvaluation(fetchFromServer = true)
                }
            }
            if (value == null || condition.targetValue == null) {
                return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
            }
            when (condition.operator) {
                "gt" -> {
                    return ConfigEvaluation(fetchFromServer = false, value as Int > condition.targetValue as Int)
                }
                "gte" -> {
                    return ConfigEvaluation(fetchFromServer = false, value as Int >= condition.targetValue as Int)
                }
                "lt" -> {
                    return ConfigEvaluation(fetchFromServer = false, (value as Int) < condition.targetValue as Int)
                }
                "lte" -> {
                    return ConfigEvaluation(fetchFromServer = false, value as Int <= condition.targetValue as Int)
                }

                "version_gt" -> {
                    var sourceVersion = ComparableVersion(value)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                            false,
                            sourceVersion.compareTo(targetVersion) > 0
                    )
                }
                "version_gte" -> {
                    var sourceVersion = ComparableVersion(value)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                            false,
                            sourceVersion.compareTo(targetVersion) >= 0
                    )
                }
                "version_lt" -> {
                    var sourceVersion = ComparableVersion(value)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                            fetchFromServer = false,
                            sourceVersion.compareTo(targetVersion) < 0
                    )
                }
                "version_lte" -> {
                    var sourceVersion = ComparableVersion(value)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                            fetchFromServer = false,
                            sourceVersion.compareTo(targetVersion) <= 0
                    )
                }
                "version_eq" -> {
                    var sourceVersion = ComparableVersion(value)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                            fetchFromServer = false,
                            sourceVersion.compareTo(targetVersion) == 0
                    )
                }
                "version_neq" -> {
                    var sourceVersion = ComparableVersion(value)
                    var targetVersion = ComparableVersion(condition.targetValue as String)
                    return ConfigEvaluation(
                            fetchFromServer = false,
                            sourceVersion.compareTo(targetVersion) != 0
                    )
                }

                "any" -> {
                    return ConfigEvaluation(
                            fetchFromServer = false,
                            (condition.targetValue as Array<String>).contains(value)
                    )
                }
                "none" -> {
                    return ConfigEvaluation(
                            fetchFromServer = false,
                            !(condition.targetValue as Array<String>).contains(value)
                    )
                }

                "str_starts_with_any" -> {
                    for (match in condition.targetValue as Array<String>) {
                        if (value.startsWith(match)) {
                            return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                        }
                    }
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                }
                "str_ends_with_any" -> {
                    for (match in condition.targetValue as Array<String>) {
                        if (value.endsWith(match)) {
                            return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                        }
                    }
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                }
                "str_contains_any" -> {
                    for (match in condition.targetValue as Array<String>) {
                        if (value.contains(match)) {
                            return ConfigEvaluation(fetchFromServer = false, booleanValue = true)
                        }
                    }
                    return ConfigEvaluation(fetchFromServer = false, booleanValue = false)
                }
                "str_matches" -> {
                    if (value.matches(Regex(condition.targetValue as String))) {
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

    private fun getFromUserAgent(user: StatsigUser?, field: String): String? {
        val ua = getFromUser(user, "userAgent") ?: return null
        val parsed = uaParser?.parse(ua as String) ?: return null
        when (field) {
            "os_name" -> {
                if (parsed.platform.toLowerCase().startsWith("win")) {
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

    private fun getFromUser(user: StatsigUser?, field: String): String? {
        if (user == null) {
            return null
        }
        val userJson = Gson().toJsonTree(user).asJsonObject
        if (userJson[field] == null && userJson["custom"] != null) {
            return Gson().toJsonTree(userJson["custom"]).asJsonObject[field]?.asString
        } else {
            return userJson[field]?.asString
        }
    }

    private fun getHashedValue(input: String): BigInteger {
        val md = MessageDigest.getInstance("SHA-256")
        val inputBytes = input.toByteArray()
        val bytes = md.digest(inputBytes)
        return BigInteger(1, bytes)
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
}
