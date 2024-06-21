package com.statsig.sdk

private const val IP_TABLE_FILE: String = "ip_supalite.table"
private const val NULL_CC: String = "--"
private const val LOOKUP_TABLE_TERMINATOR: Int = '*'.code

class CountryLookup {
    companion object {
        val countryCodes: MutableList<String> = mutableListOf()
        val ipRanges: MutableList<Long> = mutableListOf()

        private val countryTable: MutableList<String> = mutableListOf()
        private var initialized = false
        private val lock = Any()

        @JvmStatic
        fun initialize() {
            synchronized(lock) {
                if (initialized) {
                    return
                }

                // optimistically set initialized to true - otherwise we could reinitialize every check
                initialized = true

                val resourceAsStream = CountryLookup::class.java.classLoader.getResourceAsStream(IP_TABLE_FILE)
                resourceAsStream?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    initializeWithBytes(bytes)
                } ?: return
            }
        }

        fun cleanup() {
            synchronized(lock) {
                this.countryCodes.clear()
                this.ipRanges.clear()
                this.countryTable.clear()
                initialized = false
            }
        }

        @JvmStatic
        fun lookupIPString(ipAddressString: String): String? {
            initialize()
            if (ipAddressString.isEmpty()) {
                return null
            }

            val components = ipAddressString.split(".")
            if (components.size != 4) {
                return null
            }

            return try {
                val ipNumber = components[0].toLong() * 16777216 +
                    components[1].toLong() * 65536 +
                    components[2].toLong() * 256 +
                    components[3].toLong()

                lookupIPNumber(ipNumber)
            } catch (_e: Exception) {
                null
            }
        }

        @JvmStatic
        fun lookupIPNumber(ipNumber: Long): String? {
            initialize()
            val index = binarySearch(ipNumber)
            val cc = countryCodes[index]
            if (cc == NULL_CC) {
                return null
            }
            return cc
        }

        /**
         * The binary is packed as follows:
         * c1.c2.c3.....**: Country code look up table, terminated by **

         * n1.c: if n is < 240, c is country code index
         * 242.n2.n3.c: if n >= 240 but < 65536. n2 being lower order byte
         * 243.n2.n3.n4.c: if n >= 65536. n2 being lower order byte
         */
        private fun initializeWithBytes(bytes: ByteArray) {
            var index = 0
            while (index < bytes.size) {
                val c1 = bytes[index++]
                val c2 = bytes[index++]

                countryTable.add(String(byteArrayOf(c1, c2)))
                if (c1.toInt() == LOOKUP_TABLE_TERMINATOR) {
                    break
                }
            }
            var lastEndRange: Long = 0
            while (index < bytes.size) {
                var count: Long = 0
                val n1 = bytes[index++].toInt().and(0xff)
                when {
                    n1 < 240 -> {
                        count = n1.toLong()
                    }
                    n1 == 242 -> {
                        val n2 = bytes[index++].toInt().and(0xff)
                        val n3 = bytes[index++].toInt().and(0xff)
                        count = (n2.or((n3 shl 8))).toLong()
                    }
                    n1 == 243 -> {
                        val n2 = bytes[index++].toInt().and(0xff)
                        val n3 = bytes[index++].toInt().and(0xff)
                        val n4 = bytes[index++].toInt().and(0xff)
                        count = ((n2.or((n3 shl 8))).or((n4 shl 16))).toLong()
                    }
                }
                lastEndRange += count * 256

                val cc = bytes[index++].toInt().and(0xff)

                ipRanges.add(lastEndRange)
                countryCodes.add(countryTable[cc])
            }

            initialized = true
        }

        private fun binarySearch(ipNumber: Long): Int {
            var min = 0
            var max = ipRanges.size - 1

            while (min < max) {
                val mid = (min + max) shr 1
                if (ipRanges[mid] <= ipNumber) {
                    min = mid + 1
                } else {
                    max = mid
                }
            }

            return min
        }
    }
}
