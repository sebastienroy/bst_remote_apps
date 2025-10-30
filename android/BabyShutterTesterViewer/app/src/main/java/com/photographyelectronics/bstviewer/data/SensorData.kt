package com.photographyelectronics.bstviewer.data

import kotlinx.serialization.Serializable

@Serializable
data class SensorData(
    val effectiveTime: Int,
    val totalTime: Int,
    val relativeSignal: Float,
    val maxRelativeSignal: Float
)

val DefaultSensorData = SensorData(
    0,
    0,
    0.0F,
    0.0F
)

