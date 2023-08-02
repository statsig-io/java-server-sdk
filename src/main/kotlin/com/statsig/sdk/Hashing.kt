package com.statsig.sdk

import java.security.MessageDigest
import java.util.Base64

enum class HashAlgo {
    SHA256,
    DJB2,
    NONE,
}

class Hashing {
    companion object {
        fun djb2(input: String): String {
            var hash = 0
            for (element in input) {
                hash = (hash shl 5) - hash + element.code
                hash = hash and hash // Convert to 32-bit integer
            }
            return hash.toUInt().toString()
        }

        fun sha256(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val value = input.toByteArray(Charsets.UTF_8)
            val bytes = md.digest(value)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }
}
