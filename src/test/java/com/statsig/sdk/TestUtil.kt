package com.statsig.sdk

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
        internal fun captureEvents(eventLogInputCompletable: CompletableDeferred<LogEventInput>): Array<StatsigEvent> = runBlocking {
            val logs = withTimeout(TEST_TIMEOUT) {
                eventLogInputCompletable.await()
            }

            logs.events.sortBy { it.time }
            return@runBlocking logs.events
        }
    }
}
