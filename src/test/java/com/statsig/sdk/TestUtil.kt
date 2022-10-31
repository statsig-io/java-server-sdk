package com.statsig.sdk

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy

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
    }
}
