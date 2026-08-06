package com.tilda.myble.peripheral

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tilda.myble.peripheral.ui.theme.MyBLETheme

class MainActivity : ComponentActivity() {
    private lateinit var blePeripheralManager: BlePeripheralManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            blePeripheralManager.startAdvertising()
        } else {
            blePeripheralManager.startAdvertising()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blePeripheralManager = BlePeripheralManager(this)

        setContent {
            MyBLETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PeripheralScreen(blePeripheralManager) {
                        requestPermissions()
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        requestPermissionLauncher.launch(permissions)
    }

    override fun onDestroy() {
        super.onDestroy()
        // BEST PRACTICE: Clean up BLE resources when activity/app is destroyed.
        blePeripheralManager.stop()
    }
}

@Composable
fun PeripheralScreen(
    bleManager: BlePeripheralManager,
    onStartAdvertisingClick: () -> Unit
) {
    val status by bleManager.status.collectAsState()
    val receivedMessage by bleManager.receivedMessage.collectAsState()
    val isAdvertising by bleManager.isAdvertising.collectAsState()
    var messageToSend by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("BLE Peripheral", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Received: $receivedMessage", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartAdvertisingClick,
                enabled = !isAdvertising
            ) {
                Text("Start Advertising")
            }
            
            Button(
                onClick = { bleManager.stopAdvertising() },
                enabled = isAdvertising
            ) {
                Text("Stop")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = messageToSend,
            onValueChange = { messageToSend = it },
            label = { Text("Message to Central") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { bleManager.sendMessage(messageToSend) }) {
            Text("Send Message")
        }
    }
}
