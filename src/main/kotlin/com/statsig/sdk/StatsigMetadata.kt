package com.statsig.sdk

import com.google.gson.annotations.SerializedName
import java.util.Properties
import java.util.UUID

private const val VERSION = "1.28.0"

internal data class StatsigMetadata(@SerializedName("sdkType") var sdkType: String = "java-server", @SerializedName("sessionID") var sessionID: String = UUID.randomUUID().toString(), @SerializedName("languageVersion") var languageVersion: String = System.getProperty("java.version"), @SerializedName("exposureLoggingDisabled") var exposureLoggingDisabled: Boolean? = null) {
    @SerializedName("sdkVersion")
    var sdkVersion: String = try {
        val properties = Properties()
        properties.load(
            StatsigMetadata::class.java.getResourceAsStream("/statsigsdk.properties"),
        )
        properties.getProperty("version")
    } catch (e: Exception) {
        VERSION
    }
    fun asJson(): String {
        val gson = Utils.getGson()
        return gson.toJson(this)
    }
}
