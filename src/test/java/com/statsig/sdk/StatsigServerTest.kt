package com.statsig.sdk

import junit.framework.TestCase.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class StatsigServerTest {
    @Test
    fun `test batchGetExperimentsAsync returns multiple experiments correctly`() = runBlocking {
        val server = StatsigServer.create()
        server.initialize(
            "secret-key",
            StatsigOptions()
        )
        
        val user = StatsigUser("123")
        val experimentNames = listOf("experiment_1", "experiment_2", "experiment_3")
        
        val result = server.batchGetExperimentsAsync(user, experimentNames).get()
        
        assertNotNull(result)
        assertEquals(3, result.size)
        experimentNames.forEach { name ->
            assertTrue(result.containsKey(name))
            val config = result[name]
            assertNotNull(config)
            assertEquals(name, config?.name)
            
            val singleResult = server.getExperimentAsync(user, name).get()
            assertEquals(singleResult.value, config?.value)
            assertEquals(singleResult.ruleID, config?.ruleID)
        }
    }

    @Test
    fun `test batchGetExperimentsAsync returns empty configs when not initialized`() = runBlocking {
        val server = StatsigServer.create()
        val user = StatsigUser("123")
        val experimentNames = listOf("experiment_1", "experiment_2")
        
        val result = server.batchGetExperimentsAsync(user, experimentNames).get()
        
        assertNotNull(result)
        assertEquals(2, result.size)
        experimentNames.forEach { name ->
            assertTrue(result.containsKey(name))
            val config = result[name]
            assertNotNull(config)
            assertEquals(name, config?.name)
            assertTrue(config?.value?.isEmpty() ?: false)
        }
    }

    @Test
    fun `test batchGetExperimentsAsync with empty experiment list`() = runBlocking {
        val server = StatsigServer.create()
        server.initialize(
            "secret-key",
            StatsigOptions()
        )
        
        val user = StatsigUser("123")
        val experimentNames = emptyList<String>()
        
        val result = server.batchGetExperimentsAsync(user, experimentNames).get()
        
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `test batchGetExperimentsAsync results match individual getExperiment calls`() = runBlocking {
        val server = StatsigServer.create()
        server.initialize(
            "secret-key",
            StatsigOptions()
        )
        
        val user = StatsigUser("123")
        val experimentNames = listOf("experiment_1", "experiment_2")
        
        val batchResults = server.batchGetExperimentsAsync(user, experimentNames).get()
        val individualResults = experimentNames.associateWith { 
            server.getExperimentAsync(user, it).get() 
        }
        
        assertEquals(individualResults.size, batchResults.size)
        experimentNames.forEach { name ->
            val batchConfig = batchResults[name]
            val individualConfig = individualResults[name]
            
            assertNotNull(batchConfig)
            assertNotNull(individualConfig)
            assertEquals(individualConfig?.value, batchConfig?.value)
            assertEquals(individualConfig?.ruleID, batchConfig?.ruleID)
            assertEquals(individualConfig?.name, batchConfig?.name)
        }
    }
}