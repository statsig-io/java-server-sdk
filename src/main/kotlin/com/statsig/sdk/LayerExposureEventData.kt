package com.statsig.sdk

class LayerExposureEventData internal constructor (
    val user: StatsigUser,
    val layer: Layer,
    val parameterName: String,
    val metadata: String,
) {
    val eventName: String = "statsig::layer_exposure"
    val eventValue: Any? = null
}