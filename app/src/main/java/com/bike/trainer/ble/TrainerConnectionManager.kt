package com.bike.trainer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Owns the Bluetooth LE connection to a smart trainer. Handles scanning,
 * connecting, subscribing to live data, and — when the trainer supports FTMS —
 * pushing the simulated road grade so resistance tracks the terrain and gears.
 *
 * Callers must hold the relevant runtime Bluetooth permissions before invoking
 * scan/connect; the UI layer requests them.
 */
@SuppressLint("MissingPermission")
class TrainerConnectionManager(private val appContext: Context) : BleSensor {

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(TrainerConnectionState.Idle)
    override val connectionState: StateFlow<TrainerConnectionState> = _connectionState.asStateFlow()

    private val _controlMode = MutableStateFlow(TrainerControlMode.Unknown)
    val controlMode: StateFlow<TrainerControlMode> = _controlMode.asStateFlow()

    private val _trainerData = MutableStateFlow(TrainerData())
    val trainerData: StateFlow<TrainerData> = _trainerData.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredTrainer>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredTrainer>> = _discovered.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var controlPoint: BluetoothGattCharacteristic? = null

    // Single-threaded GATT operation queue: Android only allows one in flight.
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    // Latest grade requested while a write was busy, so we always send the newest.
    private var pendingGradePercent: Double? = null
    private var lastSentGradePercent: Double = Double.NaN
    private var lastSentTargetWatts: Int = -1

    val isBluetoothReady: Boolean get() = adapter?.isEnabled == true

    // ----------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            val entry = DiscoveredTrainer(device.address, name, result.rssi)
            val current = _discovered.value
            if (current.none { it.address == entry.address }) {
                _discovered.value = current + entry
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
            _connectionState.value = TrainerConnectionState.Failed
        }
    }

    override fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _connectionState.value = TrainerConnectionState.Failed
            return
        }
        _discovered.value = emptyList()
        _connectionState.value = TrainerConnectionState.Scanning

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(TrainerProfiles.FTMS_SERVICE))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(TrainerProfiles.CYCLING_POWER_SERVICE))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
    }

    override fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value == TrainerConnectionState.Scanning) {
            _connectionState.value = TrainerConnectionState.Idle
        }
    }

    // --------------------------------------------------------------- connection

    override fun connect(address: String) {
        stopScan()
        val device = adapter?.getRemoteDevice(address) ?: return
        _connectionState.value = TrainerConnectionState.Connecting
        _connectedDeviceName.value = device.name
        resetGattState()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        resetGattState()
        gatt = null
        _connectionState.value = TrainerConnectionState.Disconnected
        _connectedDeviceName.value = null
        _controlMode.value = TrainerControlMode.Unknown
    }

    private fun resetGattState() {
        opQueue.clear()
        opInFlight = false
        controlPoint = null
        pendingGradePercent = null
        lastSentGradePercent = Double.NaN
        lastSentTargetWatts = -1
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = TrainerConnectionState.Connecting
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = TrainerConnectionState.Disconnected
                    _controlMode.value = TrainerControlMode.Unknown
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = TrainerConnectionState.Failed
                return
            }
            val ftms = g.getService(TrainerProfiles.FTMS_SERVICE)
            if (ftms != null) {
                setupFtms(g, ftms)
            } else {
                val cps = g.getService(TrainerProfiles.CYCLING_POWER_SERVICE)
                if (cps != null) {
                    setupCyclingPower(g, cps)
                } else {
                    _connectionState.value = TrainerConnectionState.Failed
                }
            }
        }

        @Deprecated("Compatible callback for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            handleNotification(ch.uuid, ch.value ?: return)
        }

        // API 33+ delivers the value directly; forward to the shared handler.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(ch.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            operationCompleted()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            // After a control-point write completes, flush any newer grade request.
            operationCompleted()
            flushPendingGrade()
        }
    }

    private fun setupFtms(g: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        _controlMode.value = TrainerControlMode.Simulation
        controlPoint = service.getCharacteristic(TrainerProfiles.FITNESS_MACHINE_CONTROL_POINT)

        service.getCharacteristic(TrainerProfiles.INDOOR_BIKE_DATA)?.let { ch ->
            enqueue { subscribe(g, ch, indicate = false) }
        }
        controlPoint?.let { cp ->
            enqueue { subscribe(g, cp, indicate = true) }
            enqueue { writeControlPoint(byteArrayOf(TrainerProfiles.ControlPoint.REQUEST_CONTROL)) }
            enqueue { writeControlPoint(byteArrayOf(TrainerProfiles.ControlPoint.START_OR_RESUME)) }
        }
        runQueue()
        _connectionState.value = TrainerConnectionState.Connected
    }

    private fun setupCyclingPower(g: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        _controlMode.value = TrainerControlMode.PowerOnly
        service.getCharacteristic(TrainerProfiles.CYCLING_POWER_MEASUREMENT)?.let { ch ->
            enqueue { subscribe(g, ch, indicate = false) }
        }
        runQueue()
        _connectionState.value = TrainerConnectionState.Connected
    }

    private fun handleNotification(uuid: java.util.UUID, value: ByteArray) {
        val sample = when (uuid) {
            TrainerProfiles.INDOOR_BIKE_DATA -> TrainerProfiles.parseIndoorBikeData(value)
            TrainerProfiles.CYCLING_POWER_MEASUREMENT -> TrainerProfiles.parseCyclingPower(value)
            else -> return
        }
        val prev = _trainerData.value
        _trainerData.value = prev.copy(
            powerWatts = sample.powerWatts ?: prev.powerWatts,
            cadenceRpm = sample.cadenceRpm?.toInt() ?: prev.cadenceRpm,
            speedKmh = sample.speedKmh ?: prev.speedKmh,
            heartRate = sample.heartRate ?: prev.heartRate,
            hasPower = prev.hasPower || sample.powerWatts != null,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    // ----------------------------------------------------------- resistance API

    /**
     * Push the desired simulated road grade (terrain + gear offset) to the
     * trainer. No-ops on power-only trainers. Coalesces rapid updates so only
     * the most recent grade is ever in flight.
     */
    fun setSimulationGrade(gradePercent: Double) {
        if (_controlMode.value != TrainerControlMode.Simulation) return
        if (controlPoint == null) return
        // Avoid spamming identical values.
        if (!lastSentGradePercent.isNaN() &&
            kotlin.math.abs(gradePercent - lastSentGradePercent) < 0.05
        ) {
            return
        }
        pendingGradePercent = gradePercent
        flushPendingGrade()
    }

    private fun flushPendingGrade() {
        val grade = pendingGradePercent ?: return
        if (opInFlight) return // will be retried from onCharacteristicWrite
        pendingGradePercent = null
        lastSentGradePercent = grade
        enqueue { writeControlPoint(TrainerProfiles.buildSimulationParameters(grade)) }
        runQueue()
    }

    /**
     * Push an ERG target power (watts) to the trainer for a structured workout.
     * Only changes-of-value are sent; no-ops without an FTMS control point.
     */
    fun setTargetPower(watts: Int) {
        if (controlPoint == null) return
        if (watts == lastSentTargetWatts) return
        lastSentTargetWatts = watts
        enqueue { writeControlPoint(TrainerProfiles.buildTargetPower(watts)) }
        runQueue()
    }

    // ----------------------------------------------------------- GATT plumbing

    private fun enqueue(op: () -> Unit) {
        opQueue.add(op)
    }

    private fun runQueue() {
        if (opInFlight) return
        val next = opQueue.poll() ?: return
        opInFlight = true
        next()
    }

    private fun operationCompleted() {
        opInFlight = false
        runQueue()
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, ch: BluetoothGattCharacteristic, indicate: Boolean) {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(TrainerProfiles.CLIENT_CHARACTERISTIC_CONFIG)
        if (cccd == null) {
            operationCompleted()
            return
        }
        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        cccd.value = value
        g.writeDescriptor(cccd)
    }

    @Suppress("DEPRECATION")
    private fun writeControlPoint(payload: ByteArray) {
        val cp = controlPoint
        val g = gatt
        if (cp == null || g == null) {
            operationCompleted()
            return
        }
        cp.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        cp.value = payload
        val ok = g.writeCharacteristic(cp)
        if (!ok) operationCompleted()
    }

    companion object {
        private const val TAG = "TrainerConnection"
    }
}
