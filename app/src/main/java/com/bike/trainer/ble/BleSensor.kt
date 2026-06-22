package com.bike.trainer.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Common surface for a scannable / connectable BLE sensor (trainer, heart-rate
 * strap, gear controller). Lets a single scan UI drive any of them.
 */
interface BleSensor {
    val connectionState: StateFlow<TrainerConnectionState>
    val discovered: StateFlow<List<DiscoveredTrainer>>
    val connectedDeviceName: StateFlow<String?>

    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
}
