package com.statsig.sdk.network

import com.statsig.sdk.Diagnostics
import com.statsig.sdk.NetworkProtocol
import com.statsig.sdk.StatsigEvent
import kotlinx.coroutines.flow.Flow

internal interface INetworkWorker {
    val type: NetworkProtocol
    val isPullWorker: Boolean
    val configSpecsFlow: Flow<String>
    val idListsFlow: Flow<String>
    fun initializeFlows()
    suspend fun downloadConfigSpecs(sinceTime: Long): String?
    suspend fun getIDLists(): String?
    suspend fun logEvents(events: List<StatsigEvent>)
    fun setDiagnostics(diagnostics: Diagnostics)
    fun shutdown()
}
