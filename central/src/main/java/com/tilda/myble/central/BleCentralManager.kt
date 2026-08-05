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

@SuppressLint("MissingPermission") // در محیط واقعی پرمیشن‌ها حتما بررسی شوند
class BleCentralManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner
    
    private var bluetoothGatt: BluetoothGatt? = null
    
    // استیت‌های مربوط به وضعیت اتصال و پیام دریافتی
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status

    private val _receivedMessage = MutableStateFlow("No message yet")
    val receivedMessage: StateFlow<String> = _receivedMessage

    companion object {
        // همان UUIDهایی که در بخش Peripheral استفاده کردیم
        val SERVICE_UUID: UUID = UUID.fromString("8C380000-10BD-4FDB-BAF6-90D19CA1FB24")
        val CHAR_UUID: UUID = UUID.fromString("8C380001-10BD-4FDB-BAF6-90D19CA1FB24")
    }

    // کالبک مربوط به اسکن کردن
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d("BleCentral", "Found device: ${device.address}")
            _status.value = "Found ${device.address}, connecting..."
            
            // متوقف کردن اسکن بعد از پیدا کردن دستگاه
            stopScanning()
            
            // اتصال به دستگاه
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = "Scan failed: $errorCode"
            Log.e("BleCentral", "Scan failed with error: $errorCode")
        }
    }

    // کالبک مربوط به ارتباط GATT (کلاینت)
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _status.value = "Connected. Discovering services..."
                // بعد از اتصال باید سرویس‌های دستگاه مقابل رو کشف کنیم
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _status.value = "Disconnected"
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _status.value = "Services discovered. Ready."
                
                // فعال کردن نوتیفیکیشن برای دریافت پیام‌ها از Peripheral
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                }
            }
        }

        // وقتی Peripheral دیتایی رو از طریق نوتیفیکیشن ارسال می‌کنه
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                val message = String(characteristic.value)
                _receivedMessage.value = message
                Log.d("BleCentral", "Received message: $message")
            }
        }
    }

    fun startScanning() {
        if (scanner == null || !bluetoothAdapter!!.isEnabled) {
            _status.value = "Bluetooth is disabled"
            return
        }

        // فیلتر برای پیدا کردن فقط دستگاه‌هایی که سرویس مدنظر رو تبلیغ می‌کنن
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
            
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _status.value = "Scanning for peripheral..."
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    fun stopScanning() {
        scanner?.stopScan(scanCallback)
        if (_status.value.startsWith("Scanning")) {
            _status.value = "Scan stopped"
        }
    }

    fun sendMessage(message: String) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHAR_UUID) ?: return

        characteristic.value = message.toByteArray()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // ارسال پیام (Write) به Peripheral
        gatt.writeCharacteristic(characteristic)
        Log.d("BleCentral", "Sent message: $message")
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }
}
