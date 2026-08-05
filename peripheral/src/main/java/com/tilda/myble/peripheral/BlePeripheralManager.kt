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

@SuppressLint("MissingPermission") // در محیط واقعی باید حتما پرمیشن‌ها چک شوند
class BlePeripheralManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null

    // استیت‌های مربوط به وضعیت
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val _receivedMessage = MutableStateFlow("No message yet")
    val receivedMessage: StateFlow<String> = _receivedMessage

    companion object {
        // یک UUID اختصاصی برای سرویس و مشخصه (Characteristic)
        val SERVICE_UUID: UUID = UUID.fromString("8C380000-10BD-4FDB-BAF6-90D19CA1FB24")
        val CHAR_UUID: UUID = UUID.fromString("8C380001-10BD-4FDB-BAF6-90D19CA1FB24")
    }

    // کالبک مربوط به Advertising
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _status.value = "Advertising started..."
            Log.d("BlePeripheral", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            _status.value = "Advertising failed: $errorCode"
            Log.e("BlePeripheral", "Advertising failed with error $errorCode")
        }
    }

    // کالبک مربوط به GATT Server
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
    }

    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _status.value = "Bluetooth is disabled"
            return
        }

        // راه‌اندازی GATT Server
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        
        // ساخت سرویس و مشخصه
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)

        // تنظیمات Advertising
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser != null) {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            _status.value = "Starting advertising..."
        } else {
            _status.value = "Advertiser not available"
        }
    }
    
    fun sendMessage(message: String) {
        val device = connectedDevice ?: return
        val service = gattServer?.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHAR_UUID) ?: return
        
        characteristic.value = message.toByteArray()
        // ارسال نوتیفیکیشن به کلاینت متصل شده
        gattServer?.notifyCharacteristicChanged(device, characteristic, false)
        Log.d("BlePeripheral", "Sent notification: $message")
    }

    fun stop() {
        val advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        _status.value = "Stopped"
    }
}
