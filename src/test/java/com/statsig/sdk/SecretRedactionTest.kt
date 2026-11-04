package com.statsig.sdk

import com.statsig.sdk.network.HTTPHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

class SecretRedactionTest {
    private val key = "secret-a+b/c ?"
    private val messages = mutableListOf<String>()
    private val logger = object : LoggerInterface {
        override fun error(message: String) { messages.add(message) }
        override fun warn(message: String) { messages.add(message) }
        override fun info(message: String) { messages.add(message) }
        override fun debug(message: String) { messages.add(message) }
        override fun setLogLevel(level: LogLevel) {}
    }
    private val options = StatsigOptions().apply { customLogger = logger }
    private val boundary = ErrorBoundary(key, options, StatsigMetadata())

    @Test
    fun redactsRawEncodedRepeatedAndCredentialPaths() {
        val encoded = URLEncoder.encode(key, "UTF-8")
        val value = "status 503 $key $key $encoded /download_config_specs/unknown.json"
        val result = boundary.redact(value)
        assertFalse(result.contains(key))
        assertFalse(result.contains(encoded))
        assertFalse(result.contains("unknown"))
        assertTrue(result.contains("status 503"))
    }

    @Test
    fun exceptionReportRedactsJsonEscapedKeysAndPreservesAuthHeader() {
        val reportKey = "secret-quote\"=+"
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(202))
        val reportOptions = StatsigOptions().apply { api = "https://example.test/$reportKey" }
        val reportBoundary = ErrorBoundary(reportKey, reportOptions, StatsigMetadata())
        reportBoundary.uri = server.url("/v1/sdk_exception").toUri()
        val extra = com.google.gson.Gson().toJson(mapOf("detail" to "failure $reportKey"))
        reportBoundary.logException("test", IOException("failure $reportKey", IOException("cause $reportKey")), extraInfo = extra)
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(request)
        val body = request!!.body.readUtf8()
        val encodedKey = com.google.gson.Gson().toJson(reportKey).removeSurrounding("\"")
        assertFalse(body.contains(reportKey))
        assertFalse(body.contains(encodedKey))
        assertFalse(body.contains(URLEncoder.encode(reportKey, "UTF-8")))
        assertEquals(reportKey, request.getHeader("STATSIG-API-KEY"))
        assertNotNull(com.google.gson.Gson().fromJson(body, Map::class.java))
        server.shutdown()
    }

    @Test
    fun shortKeyDoesNotCorruptExceptionReportSchema() {
        val shortKey = "x"
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(202))
        val reportBoundary = ErrorBoundary(shortKey, StatsigOptions(), StatsigMetadata())
        reportBoundary.uri = server.url("/v1/sdk_exception").toUri()
        reportBoundary.logException("test", IOException("error $shortKey"))
        val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(request)
        val body = com.google.gson.Gson().fromJson(request!!.body.readUtf8(), com.google.gson.JsonObject::class.java)
        assertTrue(body.has("exception"))
        assertTrue(body.has("statsigMetadata"))
        assertTrue(body.has("statsigOptions"))
        assertFalse(java.net.URLDecoder.decode(body["info"].asString, "UTF-8").contains("error $shortKey"))
        assertEquals(shortKey, request.getHeader("STATSIG-API-KEY"))
        server.shutdown()
    }

    @Test
    fun timeoutDoesNotExposeNestedCauseAndRetainsClassification() {
        val original = SocketTimeoutException("request $key timed out")
        original.initCause(IOException("nested $key"))
        val safe = boundary.redactException(original)
        assertTrue(safe is SocketTimeoutException)
        assertNull(safe.cause)
        assertFalse(safe.stackTraceToString().contains(key))
        assertTrue(safe.message!!.contains("timed out"))
    }

    @Test
    fun grpcStatusRetainsCodeWithoutLeakingDescriptionOrCause() {
        val original = io.grpc.Status.UNAVAILABLE.withDescription("request $key")
            .withCause(IOException("nested $key")).asException()
        val safe = boundary.redactException(original) as io.grpc.StatusException
        assertEquals(io.grpc.Status.Code.UNAVAILABLE, safe.status.code)
        assertNull(safe.status.cause)
        assertNull(safe.cause)
        assertFalse(safe.stackTraceToString().contains(key))
    }

    @Test
    fun httpFailureSanitizesReturnedErrorAndLogger() = runBlocking {
        val helper = HTTPHelper(options, boundary)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            throw IOException("connection failed for ${chain.request().url} $key")
        }.build()
        val (_, error) = helper.request(client, "https://example.com/download_config_specs/$key.json", null)
        assertNotNull(error)
        assertNull(error!!.cause)
        assertFalse(error.stackTraceToString().contains(key))
        assertFalse(messages.joinToString().contains(key))
        assertTrue(messages.joinToString().contains("connection failed"))
    }
}
