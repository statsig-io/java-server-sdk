package com.statsig.sdk

import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class ErrorBoundary(private val apiKey: String, private val options: StatsigOptions, private val statsigMetadata: StatsigMetadata) {
    internal var uri = URI("https://statsigapi.net/v1/sdk_exception")
    private val seen = HashSet<String>()
    private val maxInfoLength = 3000
    private val client: OkHttpClient by lazy {
        OkHttpClient()
    }
    internal var diagnostics: Diagnostics? = null
    private val coroutineScope = CoroutineScope(this.getNoopExceptionHandler() + Dispatchers.IO)

    private companion object {
        val MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    fun <T> swallowSync(tag: String, task: () -> T) {
        try {
            task()
        } catch (ex: Throwable) {
            onException(tag, ex)
        }
    }

    suspend fun swallow(tag: String, task: suspend () -> Unit) {
        capture(tag, task, {
            // no op
        })
    }

    fun getUrl(): String {
        return uri.toString()
    }

    internal fun redact(value: String): String {
        if (apiKey.isEmpty()) return redactCredentialPaths(value)
        val encoded = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        val jsonEncoded = Gson().toJson(apiKey).removeSurrounding("\"")
        var result = value.replace(apiKey, "[REDACTED]")
        result = result.replace(jsonEncoded, "[REDACTED]")
        for (variant in listOf(encoded, encoded.replace("+", "%20"))) {
            result = result.replace(Regex(Regex.escape(variant), RegexOption.IGNORE_CASE), "[REDACTED]")
        }
        return redactCredentialPaths(result)
    }

    private fun redactJson(value: com.google.gson.JsonElement): com.google.gson.JsonElement = when {
        value.isJsonObject -> com.google.gson.JsonObject().also { result ->
            value.asJsonObject.entrySet().forEach { (key, child) -> result.add(redact(key), redactJson(child)) }
        }
        value.isJsonArray -> com.google.gson.JsonArray().also { result ->
            value.asJsonArray.forEach { result.add(redactJson(it)) }
        }
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> com.google.gson.JsonPrimitive(redact(value.asString))
        else -> value.deepCopy()
    }

    private fun redactCredentialPaths(value: String): String = value.replace(
        Regex("(/download_config_specs/)[^/?\\s\"']+", RegexOption.IGNORE_CASE),
        "$1[REDACTED]"
    )

    internal fun redactException(ex: Exception): Exception {
        val message = ex.message?.let { redact(it) }
        val safe = when (ex) {
            is io.grpc.StatusException -> io.grpc.Status.fromCode(ex.status.code)
                .withDescription(ex.status.description?.let { redact(it) }).asException()
            is io.grpc.StatusRuntimeException -> io.grpc.Status.fromCode(ex.status.code)
                .withDescription(ex.status.description?.let { redact(it) }).asRuntimeException()
            is java.net.SocketTimeoutException -> java.net.SocketTimeoutException(message)
            is java.net.ConnectException -> java.net.ConnectException(message)
            is java.net.UnknownHostException -> java.net.UnknownHostException(message)
            is java.io.InterruptedIOException -> java.io.InterruptedIOException(message)
            is java.io.IOException -> java.io.IOException(message)
            is IllegalArgumentException -> IllegalArgumentException(message)
            else -> Exception("${redact(ex.javaClass.name)}: ${message ?: ""}")
        }
        safe.stackTrace = ex.stackTrace.map {
            StackTraceElement(redact(it.className), redact(it.methodName), it.fileName?.let { name -> redact(name) }, it.lineNumber)
        }.toTypedArray()
        return safe
    }

    private fun getNoopExceptionHandler(): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, _ ->
            // No-op
        }
    }

    suspend fun <T> capture(tag: String, task: suspend () -> T, recover: suspend () -> T, configName: String? = null): T {
        var markerID: String? = null
        var keyType: KeyType? = null
        return try {
            keyType = KeyType.convertFromString(tag)
            markerID = markStart(keyType, configName)
            val result = task()
            markEnd(keyType, true, configName, markerID)
            return result
        } catch (ex: Throwable) {
            onException(tag, ex, configName)
            markEnd(keyType, false, configName, markerID)
            recover()
        }
    }

    fun <T> captureSync(tag: String, task: () -> T, recover: () -> T, configName: String? = null): T {
        return try {
            task()
        } catch (ex: Throwable) {
            onException(tag, ex, configName)
            recover()
        }
    }

    internal fun logException(tag: String, ex: Throwable, configName: String? = null, extraInfo: String? = null, bypassDedupe: Boolean = false) {
        try {
            coroutineScope.launch {
                if (options.localMode || options.disableAllLogging) {
                    return@launch
                }
                if (!bypassDedupe && seen.contains(ex.javaClass.name)) {
                    return@launch
                }
                seen.add(ex.javaClass.name)

                val info = redact(ex.stackTraceToString())
                options.customLogger.debug(redact("Statsig Caught Exception: $tag: ${ex.javaClass.name}\n $info "))

                var safeInfo = URLEncoder.encode(info, StandardCharsets.UTF_8.toString())
                if (safeInfo.length > maxInfoLength) {
                    safeInfo = safeInfo.substring(0, maxInfoLength)
                }
                val gson = Gson()
                val bodyFields = com.google.gson.JsonObject().apply {
                    addProperty("tag", redact(tag))
                    addProperty("exception", redact(ex.javaClass.name))
                    addProperty("info", safeInfo)
                    add("statsigMetadata", redactJson(gson.fromJson(statsigMetadata.asJson(), com.google.gson.JsonElement::class.java)))
                    addProperty("configName", redact(configName.toString()))
                    add("statsigOptions", redactJson(gson.toJsonTree(options.getLoggingCopy())))
                    val extra = try {
                        if (extraInfo == null) com.google.gson.JsonNull.INSTANCE else gson.fromJson(extraInfo, com.google.gson.JsonElement::class.java)
                    } catch (_: com.google.gson.JsonParseException) {
                        com.google.gson.JsonPrimitive(extraInfo)
                    }
                    add("extra", redactJson(extra))
                }
                val body = gson.toJson(bodyFields)
                val req =
                    Request.Builder()
                        .url(getUrl())
                        .header("STATSIG-API-KEY", apiKey)
                        .post(body.toRequestBody(MEDIA_TYPE))
                        .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        // No-op
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // No-op
                        response.close() // Ensure response is closed to free resources
                    }
                })
            }
        } catch (_: Throwable) {
            // no-op
        }
    }

    private fun onException(tag: String, ex: Throwable, configName: String? = null) {
        if (ex is StatsigIllegalStateException ||
            ex is StatsigUninitializedException
        ) {
            throw ex
        }

        logException(tag, ex, configName)
    }

    private fun markStart(keyType: KeyType?, configName: String?): String? {
        if (diagnostics == null || keyType == null) {
            return null
        }
        return diagnostics?.markStart(keyType, context = ContextType.API_CALL, additionalMarker = Marker(configName = configName))
    }

    private fun markEnd(keyType: KeyType?, success: Boolean, configName: String?, markerID: String?) {
        if (diagnostics == null || keyType == null) {
            return
        }
        diagnostics?.markEnd(
            keyType,
            success,
            context = ContextType.API_CALL,
            additionalMarker = Marker(markerID = markerID, configName = configName),
        )
    }
}
