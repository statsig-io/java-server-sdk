package com.statsig.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class HashingTest {
    @Test
    fun testDJB2() {
        assertEquals("93029210", Hashing.djb2("apple"))
        assertEquals("2898612069", Hashing.djb2("banana"))
        assertEquals("697527913", Hashing.djb2("hash_me"))
        assertEquals("2017955735", Hashing.djb2("123-321"))
        assertEquals("1460175585", Hashing.djb2("11111111111112"))
    }

    @Test
    fun testSHA256() {
        assertEquals("OnvT4jYKPSnupDb8+35ExzXRF8QtHBg1QgtrmULdTxs=", Hashing.sha256("apple"))
        assertEquals("tJPUg2Sv5E0RwBZc9HCkFk0eJgmRHvmYvoaNRq3j3k4=", Hashing.sha256("banana"))
        assertEquals("HY1edo25e7wVKWg2OviGhHEJHdPF7rduDmJNCwPJCSc=", Hashing.sha256("hash_me"))
        assertEquals("WTyPqAIBXv0kV25q+yU5JTiHOxwfAd1o7dAPObhV+Q4=", Hashing.sha256("123-321"))
        assertEquals("vKPgOdgBx8lyd5vfoU4Hl1Rtt4yIv3IwK0aLiJUiYdU=", Hashing.sha256("11111111111112"))
    }
}
