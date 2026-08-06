package com.tilda.myble.central

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleCentralManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    
    private var bluetoothGatt: BluetoothGatt? = null
    
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val _receivedMessage = MutableStateFlow("No message yet")
    val receivedMessage: StateFlow<String> = _receivedMessage

    // Flow to hold the battery level discovered and read from the peripheral
    private val _batteryLevel = MutableStateFlow("Unknown")
    val batteryLevel: StateFlow<String> = _batteryLevel

    // Flow to list all discovered services
    private val _discoveredServices = MutableStateFlow<List<String>>(emptyList())
    val discoveredServices: StateFlow<List<String>> = _discoveredServices

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("8C380000-10BD-4FDB-BAF6-90D19CA1FB24")
        val CHAR_UUID: UUID = UUID.fromString("8C380001-10BD-4FDB-BAF6-90D19CA1FB24")
        
        // Battery Service standard UUIDs
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            _status.value = "Found ${device.address}, connecting..."
            stopScanning()
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }
        override fun onScanFailed(errorCode: Int) {
            _status.value = "Scan failed: $errorCode"
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = "GATT Error: $status"
                resetState()
                gatt.close()
                bluetoothGatt = null
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _status.value = "Connected. Discovering services..."
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _status.value = "Disconnected"
                resetState()
                gatt.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _status.value = "Services discovered."
                
                // Collect discovered services for the UI
                val servicesList = gatt.services.map { service ->
                    when (service.uuid) {
                        SERVICE_UUID -> "Custom Message Service"
                        BATTERY_SERVICE_UUID -> "Battery Service"
                        else -> "Unknown: ${service.uuid.toString().substring(4,8)}" // Usually standard SIG services have distinct 16-bit UUIDs
                    }
                }
                _discoveredServices.value = servicesList
                
                // 1. Read Battery Level
                val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                val batteryChar = batteryService?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                if (batteryChar != null) {
                    // Start an async read request. The result comes in onCharacteristicRead
                    gatt.readCharacteristic(batteryChar)
                }

                // 2. Setup Notifications for the Custom Message Service
                val msgService = gatt.getService(SERVICE_UUID)
                val msgChar = msgService?.getCharacteristic(CHAR_UUID)
                if (msgChar != null) {
                    gatt.setCharacteristicNotification(msgChar, true)
                    val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    val descriptor = msgChar.getDescriptor(cccdUuid)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        // Called when a readCharacteristic request finishes
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                val batteryLevelValue = characteristic.value[0].toInt() and 0xFF
                _batteryLevel.value = "$batteryLevelValue%"
                Log.d("BleCentral", "Read battery: $batteryLevelValue%")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                val message = String(characteristic.value)
                _receivedMessage.value = message
            }
        }
    }

    private fun resetState() {
        _receivedMessage.value = "No message yet"
        _batteryLevel.value = "Unknown"
        _discoveredServices.value = emptyList()
    }

    fun startScanning() {
        if (scanner == null || !bluetoothAdapter!!.isEnabled) {
            _status.value = "Bluetooth is disabled"
            return
        }

        resetState()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        _status.value = "Scanning for peripheral..."
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        if (scanner != null && bluetoothAdapter?.isEnabled == true) {
            scanner.stopScan(scanCallback)
            if (_status.value.startsWith("Scanning")) {
                _status.value = "Scan stopped"
            }
        }
    }

    fun sendMessage(message: String) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHAR_UUID) ?: return

        characteristic.value = message.toByteArray()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(characteristic)
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        _status.value = "Disconnecting..."
    }
}
