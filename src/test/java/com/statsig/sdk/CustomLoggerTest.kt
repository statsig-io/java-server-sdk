package com.statsig.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomLoggerTest {
    val warningMessage = mutableListOf<String>()
    val infoMessage = mutableListOf<String>()
    val server = StatsigServer.create()
    val fakeLogger = object : LoggerInterface {
        override fun warning(message: String) {
            println(message)
            warningMessage.add(message)
        }

        override fun info(message: String) {
            println(message)
            infoMessage.add(message)
        }
    }

    @Test
    fun testExceptionLogger() = runBlocking {
        server.initialize("server-secret", StatsigOptions(customLogger = fakeLogger))
        assert(warningMessage.size == 1)
        server.shutdown()
        server.checkGate(StatsigUser("user_id"), "test_gate")
        assert(warningMessage.size == 3)
    }

    @Test
    fun testDownloadConfigSpecsLogger() = runBlocking {
        val handler = MockWebServer()
        handler.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return MockResponse().setResponseCode(404)
                }
            }
        }
        server.initialize(
            "server-secret",
            StatsigOptions(
                api = handler.url("/v1").toString(),
                customLogger = fakeLogger,
            ),
        )
        assert(warningMessage.size == 1)
        assertEquals(warningMessage[0], "[Statsig]: Failed to download config specification, HTTP Response 404 received from server")
        server.shutdown()
    }
}
