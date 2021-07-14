package server

import com.google.gson.annotations.SerializedName
import java.lang.StringBuilder

/**
 * An object of properties relating to the current user
 * Provide as many as possible to take advantage of advanced conditions in the Statsig console
 * A dictionary of additional fields can be provided under the "custom" field
 * @property userID - REQUIRED - a unique identifier for the user.  Why is this required?  See https://docs.statsig.com/messages/serverRequiredUserID/
 * @property email an email associated with the current user
 * @property ip the ip address of the requests for the user
 * @property userAgent the user agent of the requests for this user
 * @property country the country location of the user
 * @property locale the locale for the user
 * @property appVersion the current version of the app
 * @property custom any additional custom user attributes for custom conditions in the console
 */
data class StatsigUser(
    @SerializedName("userID")
    var userID: String,
) {
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

    @SerializedName("statsigEnvironment")
    internal var statsigEnvironment: Map<String, String>? = null

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

        return sb.toString()
    }
}
