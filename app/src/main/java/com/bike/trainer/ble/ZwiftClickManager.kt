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

    /**
     * Emits the protobuf field number of every button just *pressed*. The Settings
     * "learn buttons" flow listens here so the rider can map their own controller
     * (the field numbers vary between models / firmware, which is why a fixed
     * guess didn't work).
     */
    private val _buttonPresses = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val buttonPresses: SharedFlow<Int> = _buttonPresses.asSharedFlow()

    // Learned mapping of protobuf field number → gear up / down. Defaults match the
    // common Zwift Click guess but can be overridden by learning in Settings.
    @Volatile private var upField = 1
    @Volatile private var downField = 2

    /** Track which fields are currently pressed so we fire on the press edge only. */
    private val pressed = HashMap<Int, Boolean>()

    /** Set the learned field numbers (persisted in app config). */
    fun setMapping(up: Int, down: Int) {
        upField = up
        downField = down
    }

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

        // Skip the leading message-type byte and walk protobuf varint fields. A
        // button reads 0 while held, non-zero (1) when released; we fire on the
        // 0-edge. Every pressed field is published for the learn-buttons UI, and
        // mapped fields emit the matching shift.
        val fields = parseVarintFields(value, startOffset = 1)
        if (fields.isEmpty()) return
        for ((field, v) in fields) {
            val now = v == 0L
            val was = pressed[field] ?: false
            if (now && !was) {
                _buttonPresses.tryEmit(field)
                when (field) {
                    upField -> _gearEvents.tryEmit(GearShift.UP)
                    downField -> _gearEvents.tryEmit(GearShift.DOWN)
                }
            }
            pressed[field] = now
        }
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

        // The handshake the app sends to unlock button notifications: ASCII
        // "RideOn". (The device replies "RideOn" + 2 status bytes on the response
        // characteristic.)
        val RIDE_ON = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E)
    }
}
