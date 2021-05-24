package server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

suspend fun main(args: Array<String>) {
    val initialize = CoroutineScope(Dispatchers.Default).async {
        StatsigServer.initialize("secret-wzolRc4LHvErMJsvMTlzVEagE1YoKlm9n53OixNnLAv", StatsigOptions())
        println(StatsigServer.checkGate(StatsigUser(), "mobile_registration").toString())
        println(StatsigServer.checkGate(StatsigUser(), "i_dont_exist").toString())
        println(StatsigServer.checkGate(StatsigUser(), "always_on_gate").toString())
    }
    initialize.await()

    for (i in 1..501) {
        StatsigServer.logEvent(null, "tore123", i * 1.0, mapOf("test" to "test2"))
    }

    delay(1000)
}
