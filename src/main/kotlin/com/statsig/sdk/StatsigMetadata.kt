package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import java.util.Properties
import java.util.UUID

private const val VERSION = "2.2.0"

internal data class StatsigMetadata(
    @SerializedName("sdkType") var sdkType: String = "java-server",
    @SerializedName("sessionID") var sessionID: String = UUID.randomUUID().toString(),
    @SerializedName("languageVersion") var languageVersion: String = System.getProperty("java.version"),
    @SerializedName("exposureLoggingDisabled") var exposureLoggingDisabled: Boolean? = null,
    @SerializedName("samplingRate") var samplingRate: Long? = null,
    @SerializedName("samplingMode") var samplingMode: String? = null,
    @SerializedName("shadowLogged") var samplingStatus: String? = null,
) {
    @SerializedName("sdkVersion")
    var sdkVersion: String = SDK_VERSION

    fun asJson(): String {
        val gson = Utils.getGson()
        return gson.toJson(this)
    }

    companion object {
        internal val SDK_VERSION: String = try {
            val properties = Properties()
            properties.load(
                StatsigMetadata::class.java.getResourceAsStream("/statsigsdk.properties"),
            )
            properties.getProperty("version") ?: VERSION
        } catch (e: Exception) {
            VERSION
        }
    }
}
