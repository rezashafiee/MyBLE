package com.tilda.myble.peripheral

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BlePeripheralManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val _receivedMessage = MutableStateFlow("No message yet")
    val receivedMessage: StateFlow<String> = _receivedMessage

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising

    companion object {
        // Custom Service
        val SERVICE_UUID: UUID = UUID.fromString("8C380000-10BD-4FDB-BAF6-90D19CA1FB24")
        val CHAR_UUID: UUID = UUID.fromString("8C380001-10BD-4FDB-BAF6-90D19CA1FB24")
        
        // BEST PRACTICE: Standard Services
        // The Bluetooth SIG defines standard UUIDs for common services like Battery, Heart Rate, etc.
        // 180F is the standard 16-bit UUID for Battery Service.
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _status.value = "Advertising started..."
            _isAdvertising.value = true
            Log.d("BlePeripheral", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            _status.value = "Advertising failed: $errorCode"
            _isAdvertising.value = false
            Log.e("BlePeripheral", "Advertising failed with error $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                _status.value = "Connected to ${device.address}"
                Log.d("BlePeripheral", "Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevice = null
                _status.value = "Disconnected"
                Log.d("BlePeripheral", "Device disconnected: ${device.address}")
            }
        }

        // When Central writes to a characteristic
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                val message = String(value)
                _receivedMessage.value = message
                Log.d("BlePeripheral", "Received message: $message")
                
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }

        // BEST PRACTICE: Handle Read Requests
        // When Central reads a characteristic (e.g. Battery Level)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d("BlePeripheral", "Read request for characteristic: ${characteristic.uuid}")
            if (characteristic.uuid == BATTERY_LEVEL_CHAR_UUID) {
                // Send dummy battery level: 85%
                val batteryLevel = byteArrayOf(85)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, batteryLevel)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }
    }

    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _status.value = "Bluetooth is disabled"
            return
        }

        if (_isAdvertising.value) return

        if (gattServer == null) {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            
            // 1. Add Custom Message Service
            val msgService = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val msgChar = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            msgService.addCharacteristic(msgChar)
            
            // 2. Add Standard Battery Service
            val batteryService = BluetoothGattService(BATTERY_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val batteryChar = BluetoothGattCharacteristic(
                BATTERY_LEVEL_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ, // Only read property
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            batteryService.addCharacteristic(batteryChar)
            
            // Add both to the GattServer
            gattServer?.addService(msgService)
            gattServer?.addService(batteryService)
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            // We usually only advertise the primary UUID, so the central can find us.
            // Other services will be discovered after connection.
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser != null) {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            _status.value = "Starting advertising..."
        }
    }
    
    fun stopAdvertising() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser != null && _isAdvertising.value) {
            advertiser.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
            if (!(_status.value.startsWith("Connected"))) {
                _status.value = "Advertising stopped"
            }
        }
    }

    fun sendMessage(message: String) {
        val device = connectedDevice ?: return
        val service = gattServer?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHAR_UUID) ?: return
        
        characteristic.value = message.toByteArray()
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
    }

    fun stop() {
        stopAdvertising()
        connectedDevice?.let {
            gattServer?.cancelConnection(it)
        }
        gattServer?.close()
        gattServer = null
        _status.value = "Stopped completely"
    }
}
