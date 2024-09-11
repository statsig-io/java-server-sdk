package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import java.lang.StringBuilder
import java.util.TreeMap

/**
 * An object of properties relating to the current user
 * Provide as many as possible to take advantage of advanced conditions in the Statsig console
 * A dictionary of additional fields can be provided under the "custom" field
 *
 * userID or at least a customID is expected: learn more https://docs.statsig.com/messages/serverRequiredUserID
 *
 * @property userID a unique identifier for the user.
 * @property customIDs a map of key-value pairs representing the ID type and value for the user
 * @property email an email associated with the current user
 * @property ip the IP address of the requests for the user
 * @property userAgent the user agent of the requests for this user
 * @property country the country location of the user
 * @property locale the locale for the user
 * @property appVersion the current version of the app
 * @property custom any additional custom user attributes for custom conditions in the console
 * @property privateAttributes any user attributes that should be used in evaluation only and removed in any logs.
 */
data class StatsigUser private constructor(
    @SerializedName("userID")
    var userID: String?,

    @SerializedName("customIDs")
    var customIDs: Map<String, String>?,

    @SerializedName("email")
    var email: String? = null,

    @SerializedName("ip")
    var ip: String? = null,

    @SerializedName("userAgent")
    var userAgent: String? = null,

    @SerializedName("country")
    var country: String? = null,

    @SerializedName("locale")
    var locale: String? = null,

    @SerializedName("appVersion")
    var appVersion: String? = null,

    @SerializedName("custom")
    var custom: Map<String, Any>? = null,

    @SerializedName("privateAttributes")
    var privateAttributes: Map<String, Any>? = null,

    @SerializedName("statsigEnvironment")
    internal var statsigEnvironment: Map<String, String>? = null
) {
    constructor(userID: String) : this(userID = userID, null)

    constructor(customIDs: Map<String, String>) : this(null, customIDs = customIDs)

    constructor(userID: String, customIDs: Map<String, String>) : this(userID = userID, customIDs = customIDs, null)

    internal fun getCopyForLogging(): StatsigUser {
        return StatsigUser(
            userID = this.userID,
            customIDs = this.customIDs,
            email = this.email,
            ip = this.ip,
            userAgent = this.userAgent,
            country = this.country,
            locale = this.locale,
            appVersion = this.appVersion,
            custom = this.custom,
            statsigEnvironment = this.statsigEnvironment
            // DO NOT copy privateAttributes to the logging copy!
        )
    }

    fun getHashWithoutStableID(): String {
        val map = TreeMap<String, Any>()
        userID?.let { map.put("userID", it) }
        email?.let { map.put("email", it) }
        ip?.let { map.put("ip", it) }
        userAgent?.let { map.put("userAgent", it) }
        country?.let { map.put("country", it) }
        locale?.let { map.put("locale", it) }
        appVersion?.let { map.put("appVersion", it) }
        statsigEnvironment?.let { map.put("statsigEnvironment", it.toSortedMap()) }
        val sortedCustomIDs = TreeMap<String, String>()
        val customIDCopy = HashMap(customIDs)
        for (key in customIDCopy.keys) {
            if (key == "stableID") {
                continue
            }
            val idValue = customIDCopy[key]
            if (idValue != null) {
                sortedCustomIDs[key] = idValue
            }
        }
        map.put("customIDs", sortedCustomIDs)
        custom?.let { map.put("custom", Utils.sortMap(it)) }
        privateAttributes?.let { map.put("privateAttributes", Utils.sortMap(it)) }
        return Hashing.djb2ForMap(map)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("StatsigUser {\n")
        sb.append("\tuserID: ").append(userID).append("\n")
        sb.append("\temail: ").append(email).append("\n")
        sb.append("\tip: ").append(ip).append("\n")
        sb.append("\tuserAgent: ").append(userAgent).append("\n")
        sb.append("\tcountry: ").append(country).append("\n")
        sb.append("\tlocale: ").append(locale).append("\n")
        sb.append("\tappVersion: ").append(appVersion).append("\n")
        sb.append("\tcustom: ").append(custom).append("\n")
        sb.append("\tstatsigEnvironment: ").append(statsigEnvironment).append("\n")
        sb.append("\tprivateAttributes: ").append(privateAttributes).append("\n")
        sb.append("\tcustomIDs: ").append(customIDs).append("\n")
        sb.append("}")
        return sb.toString()
    }

    fun toMapForLogging(): Map<String, Any?> {
        return mapOf(
            "userID" to userID,
            "userAgent" to userAgent,
            "appVersion" to appVersion,
            "country" to country,
            "custom" to custom,
            "customIDs" to customIDs,
            "email" to email,
            "ip" to ip,
            "locale" to locale,
            "statsigEnvironment" to statsigEnvironment,
        )
    }
}
