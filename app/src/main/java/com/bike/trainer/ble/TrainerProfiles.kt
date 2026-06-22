package com.bike.trainer.ble

import java.util.UUID

/**
 * Bluetooth GATT identifiers and packet parsers for the standard fitness
 * trainer profiles we speak:
 *  - FTMS (Fitness Machine Service) for indoor bike data + resistance control.
 *  - Cycling Power Service as a power/cadence fallback for power-only trainers.
 */
object TrainerProfiles {

    private fun uuid16(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    // Services
    val FTMS_SERVICE: UUID = uuid16("1826")
    val CYCLING_POWER_SERVICE: UUID = uuid16("1818")

    // FTMS characteristics
    val INDOOR_BIKE_DATA: UUID = uuid16("2ad2")
    val FITNESS_MACHINE_CONTROL_POINT: UUID = uuid16("2ad9")
    val FITNESS_MACHINE_FEATURE: UUID = uuid16("2acc")
    val FITNESS_MACHINE_STATUS: UUID = uuid16("2ada")

    // Cycling Power characteristics
    val CYCLING_POWER_MEASUREMENT: UUID = uuid16("2a63")

    // Standard Client Characteristic Configuration descriptor.
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = uuid16("2902")

    /** FTMS Control Point op-codes (subset we use). */
    object ControlPoint {
        const val REQUEST_CONTROL: Byte = 0x00
        const val RESET: Byte = 0x01
        const val SET_INDOOR_BIKE_SIMULATION: Byte = 0x11.toByte()
        const val START_OR_RESUME: Byte = 0x07
        const val STOP_OR_PAUSE: Byte = 0x08
    }

    /**
     * Build an "Set Indoor Bike Simulation Parameters" (0x11) payload.
     *
     * Field resolutions per the FTMS spec:
     *  - wind speed: 0.001 m/s, sint16
     *  - grade:      0.01 %,    sint16
     *  - crr:        0.0001,    uint8
     *  - cw (wind):  0.01 kg/m, uint8
     */
    fun buildSimulationParameters(
        gradePercent: Double,
        windSpeedMs: Double = 0.0,
        crr: Double = 0.005,
        cw: Double = 0.51,
    ): ByteArray {
        val wind = (windSpeedMs / 0.001).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val grade = (gradePercent / 0.01).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val crrVal = (crr / 0.0001).toInt().coerceIn(0, 255)
        val cwVal = (cw / 0.01).toInt().coerceIn(0, 255)

        return byteArrayOf(
            ControlPoint.SET_INDOOR_BIKE_SIMULATION,
            (wind and 0xFF).toByte(), ((wind shr 8) and 0xFF).toByte(),
            (grade and 0xFF).toByte(), ((grade shr 8) and 0xFF).toByte(),
            (crrVal and 0xFF).toByte(),
            (cwVal and 0xFF).toByte(),
        )
    }

    /**
     * Parse an FTMS Indoor Bike Data (0x2AD2) notification. Unknown/absent
     * fields are returned as null.
     */
    fun parseIndoorBikeData(data: ByteArray): TrainerSample {
        if (data.size < 2) return TrainerSample()
        val reader = LittleEndianReader(data)
        val flags = reader.uint16()

        fun bit(i: Int) = (flags shr i) and 0x1 == 1

        var speedKmh: Double? = null
        var cadenceRpm: Double? = null
        var powerWatts: Int? = null
        var heartRate: Int? = null

        // bit0 == 0 means Instantaneous Speed IS present (inverted "More Data" flag).
        if (!bit(0)) {
            speedKmh = reader.uint16() * 0.01
        }
        if (bit(1)) reader.skip(2) // average speed
        if (bit(2)) cadenceRpm = reader.uint16() * 0.5
        if (bit(3)) reader.skip(2) // average cadence
        if (bit(4)) reader.skip(3) // total distance (uint24)
        if (bit(5)) reader.skip(2) // resistance level
        if (bit(6)) powerWatts = reader.sint16()
        if (bit(7)) reader.skip(2) // average power
        if (bit(8)) reader.skip(5) // expended energy (2 + 2 + 1)
        if (bit(9)) heartRate = reader.uint8()

        return TrainerSample(
            powerWatts = powerWatts,
            cadenceRpm = cadenceRpm,
            speedKmh = speedKmh,
            heartRate = heartRate,
        )
    }

    /**
     * Parse a Cycling Power Measurement (0x2A63) notification. We only need the
     * instantaneous power (always present, right after the flags).
     */
    fun parseCyclingPower(data: ByteArray): TrainerSample {
        if (data.size < 4) return TrainerSample()
        val reader = LittleEndianReader(data)
        reader.uint16() // flags
        val power = reader.sint16()
        return TrainerSample(powerWatts = power)
    }

    /** A partial reading from a trainer notification. */
    data class TrainerSample(
        val powerWatts: Int? = null,
        val cadenceRpm: Double? = null,
        val speedKmh: Double? = null,
        val heartRate: Int? = null,
    )

    /** Minimal little-endian byte reader with a moving cursor. */
    private class LittleEndianReader(private val data: ByteArray) {
        private var pos = 0
        fun skip(n: Int) { pos += n }
        fun uint8(): Int {
            if (pos >= data.size) return 0
            return (data[pos++].toInt() and 0xFF)
        }
        fun uint16(): Int {
            val lo = uint8()
            val hi = uint8()
            return (hi shl 8) or lo
        }
        fun sint16(): Int {
            val v = uint16()
            return if (v >= 0x8000) v - 0x10000 else v
        }
    }
}
