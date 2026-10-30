package com.hbkbeats.cinecam.stabilize

/** Gyroscope samples from one recording: sensor-clock timestamps (ns) and angular rate (rad/s). */
class GyroData(
    val tNs: LongArray,
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray
)
