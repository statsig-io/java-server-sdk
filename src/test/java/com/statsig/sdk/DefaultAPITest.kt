package com.statsig.sdk

import io.mockk.every
import io.mockk.mockkConstructor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Rule
import org.junit.Test

class DefaultAPITest {
    @JvmField
    @Rule
    val retry = RetryRule(3)
    @Test
    fun testDefaultAPI() {
        val requests: MutableList<Request> = mutableListOf()
        mockkConstructor(OkHttpClient::class)
        every { anyConstructed<OkHttpClient>().newCall(any()) } answers {
            requests.add(firstArg())
            callOriginal()
        }
        runBlocking {
            Statsig.initialize("secret-key", StatsigOptions(rulesetsSyncIntervalMs = 1000000))
            Statsig.checkGate(StatsigUser("test-user"), "always_on_gate")
            Statsig.shutdown()
        }
        val downloadConfigSpecsRequests = requests.filter { it.url.toString().contains("api.statsigcdn.com/v1/download_config_specs/") }
        val getIdListsRequests = requests.filter { it.url.toString().contains("statsigapi.net/v1/get_id_lists") }
        val logEventRequests = requests.filter { it.url.toString().contains("statsigapi.net/v1/log_event") }

        assert(downloadConfigSpecsRequests.size == 1) { "Expected one request to download_config_specs, but found ${downloadConfigSpecsRequests.size}" }
        assert(getIdListsRequests.size == 1) { "Expected one request to get_id_lists, but found ${getIdListsRequests.size}" }
        assert(logEventRequests.size == 1) { "Expected one request to log_event, but found ${logEventRequests.size}" }
    }
}
