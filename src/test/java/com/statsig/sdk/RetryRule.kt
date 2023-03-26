package com.statsig.sdk

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

// https://stackoverflow.com/questions/8295100/how-to-re-run-failed-junit-tests-immediately
class RetryRule(private val retryCount: Int) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return statement(base, description)
    }

    private fun statement(base: Statement, description: Description): Statement {
        return object : Statement() {
            @Throws(Throwable::class)
            override fun evaluate() {
                var caughtThrowable: Throwable? = null

                // implement retry logic here
                for (i in 0 until retryCount) {
                    try {
                        base.evaluate()
                        return
                    } catch (t: Throwable) {
                        caughtThrowable = t
                        System.err.println(description.displayName + ": run " + (i + 1) + " failed")
                    }
                }
                System.err.println(description.displayName + ": giving up after " + retryCount + " failures")
                throw caughtThrowable!!
            }
        }
    }
}
