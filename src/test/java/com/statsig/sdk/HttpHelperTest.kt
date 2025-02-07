package com.statsig.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpHelperTest {

    @Test
    fun testMaskUrl() {
        val url = "https://api.statsig.com/v1/download_config_specs/secret-123456789.json?sinceTime=5678"
        val expected = "https://api.statsig.com/v1/download_config_specs/secret-123456****.json?sinceTime=5678"
        assertEquals(expected, getUrlForLogging(url))
    }

    @Test
    fun testSecretLessCharacter() {
        val url = "https://api.statsig.com/v1/download_config_specs/secret.json?sinceTime=5678"
        val expected = "https://api.statsig.com/v1/download_config_specs/REDACTED.json?sinceTime=5678"
        assertEquals(expected, getUrlForLogging(url))
    }

    @Test
    fun testMaskIDLists() {
        val url = "https://api.statsig.com/get_id_lists"
        val expected = "https://api.statsig.com/get_id_lists"
        assertEquals(expected, getUrlForLogging(url))
    }

    private fun getUrlForLogging(
        url: String,
    ): String {
        return url.replace(Regex("/download_config_specs/([^/]+)\\.json")) { matchResult ->
            val secretKey = matchResult.groupValues[1]
            val maskedKey = if (secretKey.length > 13) {
                "${secretKey.take(13)}****"
            } else {
                "REDACTED"
            }
            "/download_config_specs/$maskedKey.json"
        }
    }
}
