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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

/**
 * Reusable BLE scaffolding for simple notify-based sensors (heart-rate straps,
 * gear controllers). Handles scanning by service UUID, connecting, the GATT
 * operation queue, and CCCD subscriptions. Subclasses declare which service to
 * scan for and which characteristics to subscribe to, and receive notifications.
 */
@SuppressLint("MissingPermission")
abstract class SimpleBleSensor(
    private val appContext: Context,
    private val scanServiceUuid: UUID,
) : BleSensor {

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(TrainerConnectionState.Idle)
    override val connectionState: StateFlow<TrainerConnectionState> = _connectionState.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredTrainer>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredTrainer>> = _discovered.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    override val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false

    /** Characteristics (by service+characteristic) to subscribe to on connect. */
    protected abstract val subscriptions: List<Subscription>

    /** Called once subscriptions are set up, e.g. to send a handshake. */
    protected open fun onConnected(gatt: BluetoothGatt) {}

    /** Delivered for every notification on a subscribed characteristic. */
    protected abstract fun onNotification(characteristic: UUID, value: ByteArray)

    data class Subscription(val service: UUID, val characteristic: UUID, val indicate: Boolean = false)

    // --------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            val entry = DiscoveredTrainer(device.address, name, result.rssi)
            if (_discovered.value.none { it.address == entry.address }) {
                _discovered.value = _discovered.value + entry
            }
        }

        override fun onScanFailed(errorCode: Int) {
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
            ScanFilter.Builder().setServiceUuid(ParcelUuid(scanServiceUuid)).build(),
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
        resetState()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        resetState()
        gatt = null
        _connectionState.value = TrainerConnectionState.Disconnected
        _connectedDeviceName.value = null
    }

    private fun resetState() {
        opQueue.clear()
        opInFlight = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = TrainerConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = TrainerConnectionState.Failed
                return
            }
            subscriptions.forEach { sub ->
                g.getService(sub.service)?.getCharacteristic(sub.characteristic)?.let { ch ->
                    enqueue { subscribe(g, ch, sub.indicate) }
                }
            }
            runQueue()
            _connectionState.value = TrainerConnectionState.Connected
            onConnected(g)
        }

        @Deprecated("Compatible callback for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            onNotification(ch.uuid, ch.value ?: return)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(ch.uuid, value)
        }

        @Suppress("DEPRECATION")
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            operationCompleted()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            operationCompleted()
        }
    }

    /** Queue a write to a characteristic (e.g. a handshake). */
    protected fun writeCharacteristic(service: UUID, characteristic: UUID, payload: ByteArray) {
        val g = gatt ?: return
        val ch = g.getService(service)?.getCharacteristic(characteristic) ?: return
        enqueue { performWrite(g, ch, payload) }
        runQueue()
    }

    // --------------------------------------------------------------- GATT queue

    private fun enqueue(op: () -> Unit) { opQueue.add(op) }

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
        cccd.value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        g.writeDescriptor(cccd)
    }

    @Suppress("DEPRECATION")
    private fun performWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, payload: ByteArray) {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = payload
        if (!g.writeCharacteristic(ch)) operationCompleted()
    }
}
