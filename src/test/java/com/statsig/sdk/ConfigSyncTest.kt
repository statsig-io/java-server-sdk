package com.statsig.sdk

import com.statsig.sdk.network.StatsigTransport
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ConfigSyncTest {
    var dcsCalledTime = 0
    private lateinit var store: SpecStore

    @Before
    fun setup() {
        val options = StatsigOptions(rulesetsSyncIntervalMs = 100)
        val metadata = StatsigMetadata()
        val coroutineScope = CoroutineScope(SupervisorJob())
        val eb = ErrorBoundary("sdk-key", options, metadata)
        val sdkConfigs = SDKConfigs()
        // mock transport layer throw exception
        var transport = StatsigTransport("sdk-key", options, metadata, coroutineScope, eb, sdkConfigs)
        transport = spyk(transport)
        val logger = StatsigLogger(coroutineScope, transport, metadata, options, sdkConfigs)
        val diagnostics = Diagnostics(true, logger)
        store = SpecStore(transport, options, metadata, coroutineScope, eb, diagnostics, sdkConfigs, "sdk-key")
        coEvery {
            transport.downloadConfigSpecs(any())
        } answers {
            dcsCalledTime++
            throw Exception("example exception")
        }
    }

    @Test
    fun testConfigSync() = runBlocking {
        store.initialize()
        // wait for 2 second for config sync
        delay(2000)
        assert(dcsCalledTime > 3)
    }
}
