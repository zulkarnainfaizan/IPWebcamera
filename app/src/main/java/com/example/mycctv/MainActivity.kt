package com.example.mycctv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceHolder
import com.pedro.library.view.OpenGlView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.mycctv.ui.theme.MyCCTVTheme

import android.util.Log

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var streamingService by mutableStateOf<StreamingService?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.d(TAG, "onServiceConnected")
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            Log.d(TAG, "onServiceDisconnected")
            isBound = false
            streamingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        enableEdgeToEdge()
        
        val intent = Intent(this, StreamingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MyCCTVTheme {
                MainScreen(streamingService)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

@Composable
fun MainScreen(service: StreamingService?) {
    val TAG = "MainScreen"
    val context = LocalContext.current
    var isStreaming by remember { mutableStateOf(false) }
    var rtspUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("abcd1234") }
    val ipAddress = remember { NetworkUtils.getIPAddress(true) }
    val scrollState = rememberScrollState()

    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val areGranted = permissionsMap.values.all { it }
        Log.d(TAG, "Permissions granted: $areGranted")
        if (!areGranted) {
            // Permission denied handling could be added here
        }
    }

    LaunchedEffect(Unit) {
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            Log.d(TAG, "Requesting permissions")
            launcher.launch(permissions)
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "My CCTV - IP Webcam", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            // Camera Preview
            var openGlViewRef by remember { mutableStateOf<OpenGlView?>(null) }
            
            LaunchedEffect(service, openGlViewRef) {
                if (service != null && openGlViewRef != null) {
                    Log.d(TAG, "LaunchedEffect: calling service.startPreview")
                    service.startPreview(openGlViewRef!!)
                }
            }

            DisposableEffect(service) {
                onDispose {
                    Log.d(TAG, "MainScreen onDispose: calling service?.stopPreview")
                    service?.stopPreview()
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(8.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        Log.d(TAG, "AndroidView factory: creating OpenGlView")
                        OpenGlView(ctx).also { openGlViewRef = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { openGlView ->
                        Log.d(TAG, "AndroidView update: openGlView=${openGlView.hashCode()}")
                        // Preview handled by LaunchedEffect
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Credentials Fields
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Security Settings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isStreaming,
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isStreaming,
                        singleLine = true
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Connection Status", style = MaterialTheme.typography.titleMedium)
                    Text(text = "IP Address: ${if (ipAddress.isEmpty()) "Not Connected" else ipAddress}")
                    Text(text = "Port: ${service?.getPort() ?: 8080}")
                    Text(text = "ONVIF: Supported (Placeholder)")
                    if (isStreaming) {
                        Text(text = "Streaming: Active", color = MaterialTheme.colorScheme.primary)
                        Text(text = "RTSP URL: $rtspUrl", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(text = "Streaming: Inactive", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (isStreaming) {
                        service?.stopStreaming()
                        isStreaming = false
                    } else {
                        service?.setCredentials(username, password)
                        service?.startStreaming(8080)
                        isStreaming = true
                        rtspUrl = service?.getRtspUrl() ?: ""
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Configure your NVR to use the RTSP URL above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
