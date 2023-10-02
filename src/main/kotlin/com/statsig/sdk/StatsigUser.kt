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
 * @property userID  a unique identifier for the user.
 * @property customIDs  a map of key-value pairs representing the ID type and value for the user
 * @property email an email associated with the current user
 * @property ip the ip address of the requests for the user
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
) {
    constructor(userID: String) : this(userID, null)
    constructor(customIDs: Map<String, String>) : this(null, customIDs)

    @SerializedName("email")
    var email: String? = null

    @SerializedName("ip")
    var ip: String? = null

    @SerializedName("userAgent")
    var userAgent: String? = null

    @SerializedName("country")
    var country: String? = null

    @SerializedName("locale")
    var locale: String? = null

    @SerializedName("appVersion")
    var appVersion: String? = null

    @SerializedName("custom")
    var custom: Map<String, Any>? = null

    @SerializedName("privateAttributes")
    var privateAttributes: Map<String, Any>? = null

    @SerializedName("statsigEnvironment")
    internal var statsigEnvironment: Map<String, String>? = null

    internal fun getCopyForLogging(): StatsigUser {
        val userCopy = StatsigUser(userID, customIDs)
        userCopy.email = email
        userCopy.ip = ip
        userCopy.userAgent = userAgent
        userCopy.country = country
        userCopy.locale = locale
        userCopy.appVersion = appVersion
        userCopy.custom = custom
        userCopy.statsigEnvironment = statsigEnvironment
        // DO NOT copy privateAttributes to the logging copy!
        userCopy.privateAttributes = null

        return userCopy
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
        if (customIDs != null) {
            for (key in customIDs!!.keys) {
                if (key == "stableID") {
                    continue
                }
                sortedCustomIDs.put(key, customIDs!![key]!!)
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
        sb.append("\tuserID: ")
        sb.append(userID)
        sb.append("\n")

        sb.append("\temail: ")
        sb.append(email)
        sb.append("\n")

        sb.append("\tip: ")
        sb.append(ip)
        sb.append("\n")

        sb.append("\tuserAgent: ")
        sb.append(userAgent)
        sb.append("\n")

        sb.append("\tcountry: ")
        sb.append(country)
        sb.append("\n")

        sb.append("\tlocale: ")
        sb.append(locale)
        sb.append("\n")

        sb.append("\tappVersion: ")
        sb.append(appVersion)
        sb.append("\n")

        sb.append("\tcustom: ")
        sb.append(custom)
        sb.append("\n")

        sb.append("\tstatsigEnvironment: ")
        sb.append(statsigEnvironment)
        sb.append("\n}")

        sb.append("\tprivateAttributes: ")
        sb.append(privateAttributes)
        sb.append("\n")

        sb.append("\tcustomIDs: ")
        sb.append(customIDs)
        sb.append("\n")

        return sb.toString()
    }
}
