package com.statsig.sdk

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

private const val TEST_TIMEOUT = 10L

class TestUtil {
    companion object {
        fun getConfigTestValues(): Map<String, Any> {
            val string = """
            {
                "name": "",
                "groupName": "",
                "passPercentage": 100,
                "conditions": [],
                "id": "",
                "salt": "",
                "returnValue": {
                    "testString": "test",
                    "testBoolean": true,
                    "testInt": 12,
                    "testDouble": 42.3,
                    "testLong": 9223372036854775806,
                    "testArray": [ "one", "two" ],
                    "testIntArray": [ 3, 2 ],
                    "testDoubleArray": [ 3.1, 2.1 ],
                    "testBooleanArray": [ true, false ],
                    "testNested": {
                        "nestedString": "nested",
                        "nestedBoolean": true,
                        "nestedDouble": 13.74,
                        "nestedLong": 13
                    }
                }
            }
            """.trimIndent()

            val obj = GsonBuilder()
                .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                .create()
                .fromJson(string, APIRule::class.java)

            return obj.returnValue as Map<String, Any>
        }
        internal fun captureEvents(eventLogInputCompletable: CompletableDeferred<LogEventInput>, filterDiagnostics: Boolean = true): Array<StatsigEvent> = runBlocking {
            val logs = withTimeout(TEST_TIMEOUT) {
                eventLogInputCompletable.await()
            }
            val events: Array<StatsigEvent> = if (filterDiagnostics) {
                logs.events.filter { it.eventName != "statsig::diagnostics" }.toTypedArray()
            } else {
                logs.events
            }

            events.sortBy { it.time }
            return@runBlocking events
        }

        internal fun captureStatsigMetadata(
            eventLogInputCompletable: CompletableDeferred<LogEventInput>
        ): StatsigMetadata = runBlocking {
            val logs = withTimeout(TEST_TIMEOUT) {
                eventLogInputCompletable.await()
            }
            return@runBlocking logs.statsigMetadata
        }

        private fun getEvaluatorFromStatsigServer(driver: StatsigServer): Evaluator {
            val privateEvaluatorField = driver.javaClass.getDeclaredField("evaluator")
            privateEvaluatorField.isAccessible = true
            return privateEvaluatorField[driver] as Evaluator
        }

        internal fun getSpecStoreFromStatsigServer(driver: StatsigServer): SpecStore {
            val eval = getEvaluatorFromStatsigServer(driver)
            val privateSpecStoreField = eval.javaClass.getDeclaredField("specStore")
            privateSpecStoreField.isAccessible = true
            return privateSpecStoreField[eval] as SpecStore
        }

        internal fun mockLogEventEndpoint(request: RecordedRequest, eventLogInputCompletable: CompletableDeferred<LogEventInput>): MockResponse {
            var logBody = ""
            if (request.headers["Content-Encoding"] == "gzip") {
                logBody = decompress(request.body)
            } else {
                logBody = request.body.readUtf8()
            }

            eventLogInputCompletable.complete(Gson().fromJson(logBody, LogEventInput::class.java))
            return MockResponse().setResponseCode(200)
        }
        private fun decompress(buffer: Buffer): String {
            val gzipInputStream = GZIPInputStream(buffer.inputStream())
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var length: Int
            while (gzipInputStream.read(buffer).also { length = it } > 0) {
                outputStream.write(buffer, 0, length)
            }
            gzipInputStream.close()
            return outputStream.toString("UTF-8")
        }
    }
}
