package com.statsig.sdk

import io.mockk.every
import io.mockk.mockkConstructor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Before
import org.junit.Test

class APIOverrideTest {
    private var server1 = MockWebServer()
    private var server2 = MockWebServer()
    private var server3 = MockWebServer()
    val downloadConfigSpecsResponse =
        this::class.java.getResource("/download_config_specs.json")?.readText() ?: ""
    var server1Calls = mutableListOf<String>()
    var server2Calls = mutableListOf<String>()
    var server3Calls = mutableListOf<String>()

    @Before
    fun setup() {
        server1.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    server1Calls.add(request.path!!)
                    if ("/v1/log_event" in request.path!!) {
                        if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                            throw Exception("No content type set!")
                        }
                        return MockResponse().setResponseCode(200)
                    }
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    if ("/v1/get_id_lists" in request.path!!) {
                        return MockResponse().setResponseCode(200)
                    }
                    return MockResponse().setResponseCode(400)
                }
            }
        }
        server2.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    server2Calls.add(request.path!!)
                    if ("/v1/log_event" in request.path!!) {
                        if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                            throw Exception("No content type set!")
                        }
                        return MockResponse().setResponseCode(200)
                    }
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    if ("/v1/get_id_lists" in request.path!!) {
                        return MockResponse().setResponseCode(200)
                    }
                    return MockResponse().setResponseCode(400)
                }
            }
        }

        server3.apply {
            dispatcher = object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    server3Calls.add(request.path!!)
                    if ("/v1/log_event" in request.path!!) {
                        if (request.getHeader("Content-Type") != "application/json; charset=utf-8") {
                            throw Exception("No content type set!")
                        }
                        return MockResponse().setResponseCode(200)
                    }
                    if ("/v1/download_config_specs" in request.path!!) {
                        return MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse)
                    }
                    if ("/v1/get_id_lists" in request.path!!) {
                        return MockResponse().setResponseCode(200)
                    }
                    return MockResponse().setResponseCode(400)
                }
            }
        }
        server1Calls.clear()
        server2Calls.clear()
        server3Calls.clear()
    }

    @Test
    fun testAPIOverride() {
        val options = StatsigOptions(
            api = server1.url("/v1").toString(),
            apiForDownloadConfigSpecs = server2.url("/v1").toString(),
            apiForGetIdlists = server3.url("/v1").toString(),
        )
        runBlocking {
            Statsig.initialize("secret-key", options)
            Statsig.checkGate(StatsigUser("test-user"), "always_on_gate")
            Statsig.shutdown()
        }
        assert(server1Calls.size == 1)
        assert(server2Calls.size == 1)
        assert(server3Calls.size == 1)
        assert(server1Calls.filter { it.contains("log_event") }.size == 1)
        assert(server2Calls.filter { it.contains("download_config_specs") }.size == 1)
        assert(server3Calls.filter { it.contains("get_id_lists") }.size == 1)
    }

    @Test
    fun testOnlyOverrideAPI() {
        val options = StatsigOptions(
            api = server1.url("/v1").toString(),
        )
        runBlocking {
            Statsig.initialize("secret-key", options)
            Statsig.checkGate(StatsigUser("test-user"), "always_on_gate")
            Statsig.shutdown()
        }
        assert(server1Calls.size == 3)
        assert(server1Calls.filter { it.contains("log_event") }.size == 1)
        assert(server1Calls.filter { it.contains("download_config_specs") }.size == 1)
        assert(server1Calls.filter { it.contains("get_id_lists") }.size == 1)
    }

    @Test
    fun testDefaultAPI() {
        val requests: MutableList<Request> = mutableListOf()
        mockkConstructor(OkHttpClient::class)
        every { anyConstructed<OkHttpClient>().newCall(any()) } answers {
            requests.add(firstArg())
            callOriginal()
        }
        runBlocking {
            Statsig.initialize("secret-key", StatsigOptions())
            Statsig.checkGate(StatsigUser("test-user"), "always_on_gate")
            Statsig.shutdown()
        }
        assert(requests.filter { it.url.toString().contains("api.statsigcdn.com/v1/download_config_specs/") }.size == 1)
        assert(requests.filter { it.url.toString().contains("statsigapi.net/v1/get_id_lists") }.size == 1)
        assert(requests.filter { it.url.toString().contains("statsigapi.net/v1/log_event") }.size == 1)
    }
}
