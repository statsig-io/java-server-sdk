package com.statsig.sdk.network

import com.google.gson.JsonParseException
import com.statsig.sdk.*
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class HTTPHelper(
    private val options: StatsigOptions,
    private val errorBoundary: ErrorBoundary,
) {
    private var diagnostics: Diagnostics? = null
    private val logger = options.customLogger

    private val gson = Utils.getGson()
    private val json: MediaType = "application/json; charset=utf-8".toMediaType()

    fun setDiagnostics(diagnostics: Diagnostics) {
        this.diagnostics = diagnostics
    }

    suspend fun request(
        client: OkHttpClient,
        url: String,
        body: Map<String, Any>?,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Response?, Exception?> {
        val diagnosticsKey = diagnostics?.getDiagnosticKeyFromURL(url)
        val urlForLogging = getUrlForLogging(url)
        try {
            val request = Request.Builder()
                .url(url)
            if (body != null) {
                val bodyJson = gson.toJson(body)
                request.post(bodyJson.toRequestBody(json))
            }
            headers.forEach { (key, value) -> request.addHeader(key, value) }
            diagnostics?.startNetworkRequestDiagnostics(diagnosticsKey, NetworkProtocol.HTTP)
            val response = client.newCall(request.build()).await()
            diagnostics?.endNetworkRequestDiagnostics(
                diagnosticsKey,
                NetworkProtocol.HTTP,
                response.isSuccessful,
                null,
                response,
            )
            logger.info("[StatsigHTTPHelper] Received response with status code: ${response.code} and with URL: $urlForLogging")
            return Pair(response, null)
        } catch (e: Exception) {
            logger.warn("[StatsigHTTPHelper] An exception was caught: $e when hitting $urlForLogging")
            if (e is JsonParseException) {
                errorBoundary.logException(
                    "postImpl",
                    e,
                    extraInfo = urlForLogging
                )
            }
            diagnostics?.endNetworkRequestDiagnostics(diagnosticsKey, NetworkProtocol.HTTP, false, e.message, null)
            return Pair(null, e)
        }
    }

    private fun getUrlForLogging(
        url: String,
    ): String {
        return url.replace(Regex("/download_config_specs/([^/]+)\\.json")) { matchResult ->
            val secretKey = matchResult.groupValues[1]
            val maskedKey = if (secretKey.length > 13) {
                "${secretKey.take(13)}****"
            } else {
                "REDACTED"
            }
            "/download_config_specs/$maskedKey.json"
        }
    }
}
