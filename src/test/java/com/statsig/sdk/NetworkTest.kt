package com.statsig.sdk

import io.mockk.coVerifySequence
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal class NetworkTest {
    var server = MockWebServer()
    var logEventCount = 0
    val metadata = StatsigMetadata()
    val options = StatsigOptions()
    val eb = ErrorBoundary("", options, metadata)
    @Before
    fun setup() {
        server = MockWebServer()
        logEventCount = 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testRetry() = runBlocking {
        server.start()

        val errResponse = MockResponse()
        errResponse.setResponseCode(500)
        errResponse.setBody("{}")
        server.enqueue(errResponse)

        val successResponse = MockResponse()
        successResponse.setResponseCode(200)
        successResponse.setBody("{}")
        server.enqueue(successResponse)

        options.api = server.url("/v1").toString()

        val net = spyk(StatsigNetwork("secret-123", options, metadata, eb, 1))

        net.postLogs(listOf(StatsigEvent("TestEvent")), metadata)
        val request = server.takeRequest()
        assertEquals("POST /v1/log_event HTTP/1.1", request.requestLine)
        server.takeRequest()

        coVerifySequence {
            net.postLogs(any(), any())
            net.retryPostLogs(any(), any(), any(), any())
        }
    }

    @Test
    fun testLogEventRetry() = runBlocking {
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/log_event" in request.path!!) {
                        val logBody = request.body.readUtf8()
                        if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                            throw Exception("No content type set!")
                        }
                        ++logEventCount
                        return MockResponse().setResponseCode(503)
                    }
                    return MockResponse().setResponseCode(400)
                }
            }
        }
        options.api = server.url("/v1").toString()
        val net = spyk(StatsigNetwork("secret-123", options, metadata, eb))
        net.postLogs(listOf(StatsigEvent("TestEvent")), metadata)
        println(logEventCount)
        assert(logEventCount === LOG_EVENT_RETRY_COUNT + 1)
    }

    @Test
    fun testLogEventNoRetry() = runBlocking {
        server.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/log_event" in request.path!!) {
                        val logBody = request.body.readUtf8()
                        if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                            throw Exception("No content type set!")
                        }
                        ++logEventCount
                        return MockResponse().setResponseCode(400)
                    }
                    return MockResponse().setResponseCode(400)
                }
            }
        }
        options.api = server.url("/v1").toString()
        val net = spyk(StatsigNetwork("secret-123", options, metadata, eb))
        net.postLogs(listOf(StatsigEvent("TestEvent")), metadata)
        println(logEventCount)
        assert(logEventCount === 1)
    }
}
