package com.bike.trainer.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Connects to a standard Bluetooth Heart Rate Service (0x180D) strap and
 * publishes the live heart rate. Independent of the trainer so any HRM works.
 */
class HeartRateManager(appContext: Context) :
    SimpleBleSensor(appContext, HEART_RATE_SERVICE) {

    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    override val subscriptions = listOf(
        SimpleBleSensor.Subscription(HEART_RATE_SERVICE, HEART_RATE_MEASUREMENT, indicate = false),
    )

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != HEART_RATE_MEASUREMENT || value.isEmpty()) return
        val flags = value[0].toInt() and 0xFF
        val isUint16 = flags and 0x01 == 1
        val hr = if (isUint16 && value.size >= 3) {
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else if (value.size >= 2) {
            value[1].toInt() and 0xFF
        } else {
            return
        }
        if (hr in 1..255) _heartRate.value = hr
    }

    override fun disconnect() {
        super.disconnect()
        _heartRate.value = 0
    }

    private companion object {
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    }
}
