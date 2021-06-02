package server

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val MAX_EVENTS: Int = 500
const val FLUSH_TIMER_MS: Long = 60000

const val CONFIG_EXPOSURE_EVENT = "statsig::config_exposure"
const val GATE_EXPOSURE_EVENT = "statsig::gate_exposure"

class StatsigLogger(
    private val network: StatsigNetwork,
    private val statsigMetadata: Map<String, String>,
) {

    private var events: MutableList<StatsigEvent> = ArrayList()
    private var gateExposures: MutableSet<String> = HashSet()
    private var configExposures: MutableSet<String> = HashSet()
    private var timer: Job? = null

    @Synchronized
    fun log(event: StatsigEvent) {
        events.add(event)

        if (events.size >= MAX_EVENTS) {
            flush()
            return
        }

        if (events.size == 1) {
            val logger = this
            timer = GlobalScope.launch {
                delay(FLUSH_TIMER_MS)
                logger.flush()
            }
        }
    }

    @Synchronized
    fun logGateExposure(user: StatsigUser?, gateName: String, value: Boolean, ruleID: String) {
        var event = StatsigEvent(GATE_EXPOSURE_EVENT, eventValue = null, mapOf("gate" to gateName, "gateValue" to value.toString(), "ruleID" to ruleID), user, statsigMetadata)
        log(event)
    }

    @Synchronized
    fun logConfigExposure(user: StatsigUser?, configName: String, ruleID: String) {
        var event = StatsigEvent(CONFIG_EXPOSURE_EVENT, eventValue = null, mapOf("config" to configName, "ruleID" to ruleID), user, statsigMetadata)
        log(event)
    }

    @Synchronized
    fun flush() {
        if (events.size == 0 || timer == null) {
            return
        }
        timer?.cancel()

        val flushEvents: MutableList<StatsigEvent> = ArrayList(this.events.size)
        flushEvents.addAll(this.events)
        this.events = ArrayList()

        GlobalScope.launch {
            network.postLogs(flushEvents, statsigMetadata)
        }
    }
}
