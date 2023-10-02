package com.statsig.sdk

import com.google.gson.Gson
import io.mockk.unmockkAll
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ErrorBoundaryTest {
    private lateinit var boundary: ErrorBoundary
    private lateinit var server: MockWebServer
    private lateinit var statsigMetadata: StatsigMetadata

    @Before
    internal fun setup() {
        server = MockWebServer()
        server.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(req: RecordedRequest): MockResponse {
                    return MockResponse().setResponseCode(202)
                }
            }
        }
        statsigMetadata = StatsigMetadata()
        boundary = ErrorBoundary("secret-key", StatsigOptions(), statsigMetadata)
        boundary.uri = server.url("/v1/sdk_exception").toUri()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkAll()
    }

    @Test
    fun testRecoveryFromErrors() = runBlocking {
        var called = false
        boundary.capture("", { throw IOException() }, { called = true })

        assertTrue(called)
    }

    @Test
    fun testLogsToCorrectEndpoint() = runBlocking {
        boundary.swallow("") { throw IOException() }

        val req = server.takeRequest()
        assertEquals("POST /v1/sdk_exception HTTP/1.1", req.requestLine)
        assertEquals(req.getHeader("STATSIG-API-KEY"), "secret-key")
    }

    @Test
    fun testLogsExceptionDetails() = runBlocking {
        val err = IOException("Test")
        boundary.swallow("") { throw err }

        val body = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
        assertEquals(body["exception"], "java.io.IOException")
        val trace = URLEncoder.encode(err.stackTraceToString(), StandardCharsets.UTF_8.toString())
        assertEquals(body["info"], trace.substring(0, 3000))
    }

    @Test
    fun testLogsStatsigMetadata() = runBlocking {
        boundary.swallow("") { throw IOException() }

        val body = Gson().fromJson(server.takeRequest().body.readUtf8(), Map::class.java)
        assertEquals(body["statsigMetadata"], Gson().fromJson(statsigMetadata.asJson(), Map::class.java))
    }

    @Test
    fun testLogsTheSameErrorOnlyOnce() = runBlocking {
        boundary.swallow("") { throw ClassNotFoundException() }
        boundary.swallow("") { throw ClassNotFoundException() }

        assertEquals(server.requestCount, 1)
    }

    @Test
    fun testDoesNotCatchIntendedExceptions() = runBlocking {
        assertThrows(StatsigIllegalStateException::class.java) {
            runBlocking {
                boundary.swallow("") { throw StatsigIllegalStateException("") }
            }
        }

        assertThrows(StatsigUninitializedException::class.java) {
            runBlocking {
                boundary.swallow("") { throw StatsigUninitializedException("") }
            }
        }

        assertEquals(server.requestCount, 0)
    }

    @Test
    fun testSwallow() = runBlocking {
        boundary.swallow("") { throw IOException() }
        assertEquals(server.requestCount, 1)
    }

    @Test
    fun testSwallowSync() {
        boundary.swallowSync("") { throw IOException() }
        assertEquals(server.requestCount, 1)
    }
}
