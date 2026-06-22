package com.bike.trainer.ble

/** The latest merged reading from the connected trainer. */
data class TrainerData(
    val powerWatts: Int = 0,
    val cadenceRpm: Int = 0,
    val speedKmh: Double = 0.0,
    val heartRate: Int = 0,
    val hasPower: Boolean = false,
    val updatedAtMillis: Long = 0L,
)

/** A device discovered during scanning. */
data class DiscoveredTrainer(
    val address: String,
    val name: String,
    val rssi: Int,
)

/** High-level connection lifecycle the UI reacts to. */
enum class TrainerConnectionState {
    Idle,
    Scanning,
    Connecting,
    Connected,
    Disconnected,
    Failed,
}

/** Whether the connected trainer accepts resistance/simulation commands. */
enum class TrainerControlMode {
    /** FTMS control point available: real resistance/grade simulation. */
    Simulation,
    /** Power/cadence only (e.g. Cycling Power Service): no resistance control. */
    PowerOnly,
    Unknown,
}
