package com.bike.trainer.ble

import android.bluetooth.BluetoothGatt
import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID

/** A shift request from a physical controller. */
enum class GearShift { UP, DOWN }

/**
 * Connects to a Zwift physical gear controller (Click / Play) and turns its
 * button presses into [GearShift] events for the virtual gears.
 *
 * NOTE: Zwift's controller protocol is proprietary and undocumented; this is a
 * best-effort implementation based on community reverse-engineering (the
 * "RideOn" handshake + protobuf button notifications) and may need tweaking
 * against real hardware. It will never crash on unexpected data — unparseable
 * notifications are simply ignored.
 */
class ZwiftClickManager(appContext: Context) :
    SimpleBleSensor(appContext, ZWIFT_SERVICE) {

    private val _gearEvents = MutableSharedFlow<GearShift>(extraBufferCapacity = 8)
    val gearEvents: SharedFlow<GearShift> = _gearEvents.asSharedFlow()

    // Track button states so we only fire on the press edge (Zwift sends 0 when
    // a button is held and 1 when released).
    private var plusPressed = false
    private var minusPressed = false

    override val subscriptions = listOf(
        SimpleBleSensor.Subscription(ZWIFT_SERVICE, ZWIFT_MEASUREMENT, indicate = false),
        SimpleBleSensor.Subscription(ZWIFT_SERVICE, ZWIFT_RESPONSE, indicate = true),
    )

    override fun onConnected(gatt: BluetoothGatt) {
        // RideOn handshake unlocks button notifications.
        writeCharacteristic(ZWIFT_SERVICE, ZWIFT_CONTROL, RIDE_ON)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != ZWIFT_MEASUREMENT || value.size < 2) return

        // Skip the leading message-type byte and walk protobuf varint fields.
        val fields = parseVarintFields(value, startOffset = 1)
        val plusVal = fields[BUTTON_PLUS_FIELD]
        val minusVal = fields[BUTTON_MINUS_FIELD]
        if (plusVal == null && minusVal == null) return

        val plusNow = plusVal == 0L
        val minusNow = minusVal == 0L

        if (plusNow && !plusPressed) _gearEvents.tryEmit(GearShift.UP)
        if (minusNow && !minusPressed) _gearEvents.tryEmit(GearShift.DOWN)
        plusPressed = plusNow
        minusPressed = minusNow
    }

    /**
     * Minimal protobuf reader: returns the last varint value seen for each
     * field number (wire type 0). Length-delimited/fixed fields are skipped.
     */
    private fun parseVarintFields(data: ByteArray, startOffset: Int): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        var pos = startOffset
        try {
            while (pos < data.size) {
                val key = readVarint(data, pos) ?: break
                pos = key.second
                val fieldNum = (key.first ushr 3).toInt()
                when ((key.first and 0x7).toInt()) {
                    0 -> { // varint
                        val v = readVarint(data, pos) ?: break
                        out[fieldNum] = v.first
                        pos = v.second
                    }
                    1 -> pos += 8 // 64-bit
                    2 -> { // length-delimited
                        val len = readVarint(data, pos) ?: break
                        pos = len.second + len.first.toInt()
                    }
                    5 -> pos += 4 // 32-bit
                    else -> break
                }
            }
        } catch (_: Exception) {
            // Tolerant by design.
        }
        return out
    }

    /** Read a base-128 varint; returns (value, nextPos) or null. */
    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size && shift < 64) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }

    private companion object {
        val ZWIFT_SERVICE: UUID = UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1")
        val ZWIFT_MEASUREMENT: UUID = UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1")
        val ZWIFT_CONTROL: UUID = UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1")
        val ZWIFT_RESPONSE: UUID = UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1")

        // "RideOn" plus the app-hello bytes used by the controller handshake.
        val RIDE_ON = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E, 0x01, 0x02)

        const val BUTTON_PLUS_FIELD = 1
        const val BUTTON_MINUS_FIELD = 2
    }
}
