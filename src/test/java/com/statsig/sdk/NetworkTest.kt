package com.statsig.sdk

import io.mockk.coVerifySequence
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testRetry() = runBlocking {
        val server = MockWebServer()
        server.start()

        val errResponse = MockResponse()
        errResponse.setResponseCode(500)
        errResponse.setBody("{}")
        server.enqueue(errResponse)

        val successResponse = MockResponse()
        successResponse.setResponseCode(200)
        successResponse.setBody("{}")
        server.enqueue(successResponse)

        val metadata = StatsigMetadata()
        val options = StatsigOptions()
        options.api = server.url("/v1").toString()

        val eb = ErrorBoundary("", options, metadata)
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
}
