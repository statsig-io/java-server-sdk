package com.statsig.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLoggerTest {
    val warningMessage = mutableListOf<String>()
    val infoMessage = mutableListOf<String>()
    val debugMessages = mutableListOf<String>()
    val server = StatsigServer.create()
    val fakeLogger = object : LoggerInterface {
        override fun error(message: String) {
            println(message)
        }

        override fun warn(message: String) {
            println(message)
            warningMessage.add(message)
        }

        override fun info(message: String) {
            println(message)
            infoMessage.add(message)
        }

        override fun debug(message: String) {
            debugMessages.add(message)
            println(message)
        }

        override fun setLogLevel(level: LogLevel) {
        }
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
                rulesetsSyncIntervalMs = 3 * 60 * 1000L,
                customLogger = fakeLogger,
            ),
        )
        server.shutdown()
        assertTrue(warningMessage[0].contains("Failed to download config specification"))
        assertTrue(warningMessage[0].contains("HTTP Response 404 received from server"))
    }

    @Test
    fun testDefaultLogger() = runBlocking {
        val handler = MockWebServer()
        val downloadConfigSpecsResponse =
            StatsigE2ETest::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
        handler.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    return MockResponse().setResponseCode(200)
                }
            }
        }
        server.initialize(
            "server-secret",
            StatsigOptions(
                api = handler.url("/v1").toString(),
                customLogger = fakeLogger,
                logLevel = LogLevel.DEBUG
            ),
        )
        delay(10 * 1000L) // let some background syncing happen
        server.shutdown()
        assertTrue(debugMessages.size >= 4)
    }
}
