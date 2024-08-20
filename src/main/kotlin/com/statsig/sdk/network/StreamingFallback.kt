package com.statsig.sdk.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal class StreamingFallback(
    private val statsigScope: CoroutineScope,
    private val getDcsFn: suspend () -> String?,
    private val interval: Long,
) {
    private var job: Job? = null
    fun startBackup(flow: MutableSharedFlow<String>) {
        if (job == null) {
            job = statsigScope.launch {
                while (true) {
                    val config = getDcsFn.invoke()
                    if (config != null) {
                        flow.emit(config)
                    }
                    delay(interval)
                }
            }
        }
    }

    fun cancelBackup() {
        job?.cancel()
        job = null
    }
}
