package com.statsig.sdk

import com.google.gson.Gson
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.absoluteValue

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

        fun djb2ForMap(map: Map<String, Any>): String {
            val gson = Gson()
            return djb2(gson.toJson(Utils.sortMap(map)))
        }

        fun sha256(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val value = input.toByteArray(Charsets.UTF_8)
            val bytes = md.digest(value)
            return Base64.getEncoder().encodeToString(bytes)
        }

        fun sha256ToLong(input: String): Long {
            val md = MessageDigest.getInstance("SHA-256")
            val hashBytes = md.digest(input.toByteArray(Charsets.UTF_8))
            // Combine more bytes to increase randomness
            val buffer = ByteBuffer.wrap(hashBytes)
            val high = buffer.long // First 8 bytes
            val low = buffer.long // Next 8 bytes

            // Combine high and low parts to get better entropy
            return (high xor low).absoluteValue
        }
    }
}
