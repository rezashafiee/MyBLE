package com.tilda.myble.central

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
import com.tilda.myble.central.ui.theme.MyBLETheme

class MainActivity : ComponentActivity() {
    private lateinit var bleCentralManager: BleCentralManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleCentralManager.startScanning()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleCentralManager = BleCentralManager(this)

        setContent {
            MyBLETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CentralScreen(bleCentralManager) {
                        requestPermissions()
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
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
        bleCentralManager.disconnect()
        bleCentralManager.stopScanning()
    }
}

@Composable
fun CentralScreen(
    bleManager: BleCentralManager,
    onStartScanClick: () -> Unit
) {
    val status by bleManager.status.collectAsState()
    val receivedMessage by bleManager.receivedMessage.collectAsState()
    var messageToSend by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("BLE Central", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Received: $receivedMessage", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onStartScanClick) {
            Text("Start Scanning")
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = messageToSend,
            onValueChange = { messageToSend = it },
            label = { Text("Message to Peripheral") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { bleManager.sendMessage(messageToSend) }) {
            Text("Send Message")
        }
    }
}
