package com.example.whistledetector

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var _currentWhistleCount by mutableIntStateOf(0)
    private var _isAlarmTriggered by mutableStateOf(false)
    private var _isListening by mutableStateOf(false)

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WhistleService.ACTION_UPDATE_UI) {
                _currentWhistleCount = intent.getIntExtra(WhistleService.EXTRA_CURRENT_COUNT, 0)
                _isAlarmTriggered = intent.getBooleanExtra(WhistleService.EXTRA_ALARM_TRIGGERED, false)

                // If alarm triggered, we are no longer listening
                if (_isAlarmTriggered) {
                    _isListening = false
                }
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
            if (audioGranted) {
                // Good to go
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkPermissions()

        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(WhistleService.ACTION_UPDATE_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WhistleDetectorApp(
                        currentCount = _currentWhistleCount,
                        isAlarmTriggered = _isAlarmTriggered,
                        isListeningState = _isListening,
                        onStartListening = { target ->
                            _isListening = true
                            startWhistleService(target)
                        },
                        onStopListening = {
                            _isListening = false
                            stopWhistleService()
                        },
                        onStopAlarm = {
                            _isAlarmTriggered = false
                            stopAlarm()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun startWhistleService(target: Int) {
        val intent = Intent(this, WhistleService::class.java).apply {
            action = WhistleService.ACTION_START
            putExtra(WhistleService.EXTRA_TARGET_WHISTLES, target)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopWhistleService() {
        val intent = Intent(this, WhistleService::class.java).apply {
            action = WhistleService.ACTION_STOP
        }
        startService(intent) // Start service with action stop will stop it safely
    }

    private fun stopAlarm() {
        val intent = Intent(this, WhistleService::class.java).apply {
            action = WhistleService.ACTION_STOP_ALARM
        }
        startService(intent)
    }
}

@Composable
fun WhistleDetectorApp(
    currentCount: Int,
    isAlarmTriggered: Boolean,
    isListeningState: Boolean,
    onStartListening: (Int) -> Unit,
    onStopListening: () -> Unit,
    onStopAlarm: () -> Unit
) {
    var targetWhistlesText by remember { mutableStateOf("3") }
    var targetWhistles by remember { mutableIntStateOf(3) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Pressure Cooker Whistle Detector",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        if (!isListeningState && !isAlarmTriggered) {
            OutlinedTextField(
                value = targetWhistlesText,
                onValueChange = {
                    targetWhistlesText = it
                    it.toIntOrNull()?.let { validNum ->
                        if (validNum > 0) targetWhistles = validNum
                    }
                },
                label = { Text("Number of whistles to wait for") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(0.8f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onStartListening(targetWhistles) },
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Start Listening")
            }
        }

        if (isListeningState) {
            Text(
                text = "Listening in background...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Detected: $currentCount / $targetWhistles",
                style = MaterialTheme.typography.displaySmall
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onStopListening,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Stop")
            }
        }

        if (isAlarmTriggered) {
            Text(
                text = "Target reached!",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Button(
                onClick = onStopAlarm,
                modifier = Modifier.fillMaxWidth(0.8f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text("TURN OFF COOKER & ALARM", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
