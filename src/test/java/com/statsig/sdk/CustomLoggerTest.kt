package com.statsig.sdk

import kotlinx.coroutines.runBlocking
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
        assert(warningMessage.size == 2) // One from network one from gson
        server.shutdown()
        server.checkGate(StatsigUser("user_id"), "test_gate")
        assert(infoMessage.size == 1)
    }
}
