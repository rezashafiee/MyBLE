package com.tilda.myble.central

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        } else {
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
    val batteryLevel by bleManager.batteryLevel.collectAsState()
    val discoveredServices by bleManager.discoveredServices.collectAsState()
    
    var messageToSend by remember { mutableStateOf("") }
    
    val isScanning = status.startsWith("Scanning")
    val isConnected = status.startsWith("Connected") || status.startsWith("Services discovered")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("BLE Central", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        Text("Status: $status", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        
        Text("Peripheral Battery: $batteryLevel", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        
        Text("Received Message: $receivedMessage", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))

        if (discoveredServices.isNotEmpty()) {
            Text("Discovered Services:", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.height(80.dp)) {
                items(discoveredServices) { service ->
                    Text("- $service", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onStartScanClick,
                enabled = !isScanning && !isConnected
            ) {
                Text("Start Scanning")
            }
            
            Button(
                onClick = {
                    if (isScanning) bleManager.stopScanning()
                    else if (isConnected) bleManager.disconnect()
                },
                enabled = isScanning || isConnected
            ) {
                Text(if (isScanning) "Stop Scanning" else "Disconnect")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = messageToSend,
            onValueChange = { messageToSend = it },
            label = { Text("Message to Peripheral") },
            enabled = isConnected
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { bleManager.sendMessage(messageToSend) },
            enabled = isConnected
        ) {
            Text("Send Message")
        }
    }
}
