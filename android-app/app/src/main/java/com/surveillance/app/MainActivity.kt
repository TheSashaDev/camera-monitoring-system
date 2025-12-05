package com.surveillance.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surveillance.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraService: CameraService? = null
    private var serviceBound = false
    private val deviceId: String by lazy { obtainDeviceId() }
    
    private var hasFrontCamera = false
    private var hasBackCamera = false

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as CameraService.LocalBinder
                cameraService = binder.getService()
                serviceBound = true
                setupServiceCallbacks()
                log("Service bound")
            } catch (e: Exception) {
                log("Service connection error: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            serviceBound = false
            log("Service unbound")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            binding.deviceIdText.text = "Device ID: ${deviceId.take(12)}..."

            val savedUrl = getSharedPreferences("config", MODE_PRIVATE)
                .getString("server_url", "http://192.168.1.100:8420") ?: ""
            binding.serverUrlInput.setText(savedUrl)

            setupCameraSelector()
            
            binding.connectButton.setOnClickListener {
                handleConnectButtonClick()
            }

            if (hasAllPermissions()) {
                startCameraService(savedUrl)
            } else {
                requestPermissions()
            }
        } catch (e: Exception) {
            log("onCreate error: ${e.message}")
        }
    }
    
    private fun setupCameraSelector() {
        // Camera selector will be updated when service connects and reports available cameras
        binding.cameraSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!serviceBound || cameraService == null) return
                
                val selected = when (position) {
                    0 -> "auto"
                    1 -> "back"
                    2 -> "front"
                    else -> "auto"
                }
                
                cameraService?.setPreferredCamera(selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun updateCameraSelector(hasFront: Boolean, hasBack: Boolean, preferred: String) {
        this.hasFrontCamera = hasFront
        this.hasBackCamera = hasBack
        
        val options = mutableListOf<String>()
        options.add("Auto (${if (hasBack) "Back" else if (hasFront) "Front" else "None"})")
        if (hasBack) options.add("Back Camera")
        if (hasFront) options.add("Front Camera")
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cameraSelector.adapter = adapter
        
        // Set current selection
        val currentPosition = when (preferred) {
            "back" -> if (hasBack) 1 else 0
            "front" -> if (hasFront) (if (hasBack) 2 else 1) else 0
            else -> 0
        }
        binding.cameraSelector.setSelection(currentPosition)
        
        // Update camera status text
        val statusText = buildString {
            append("Cameras: ")
            if (hasBack) append("Back ")
            if (hasFront) append("Front ")
            if (!hasBack && !hasFront) append("None detected!")
        }
        binding.cameraStatusText.text = statusText
    }
    
    private fun handleConnectButtonClick() {
        try {
            if (!hasAllPermissions()) {
                requestPermissions()
                return
            }

            val url = binding.serverUrlInput.text.toString().trim()
            if (url.isEmpty()) {
                log("Please enter server URL")
                return
            }

            getSharedPreferences("config", MODE_PRIVATE)
                .edit()
                .putString("server_url", url)
                .apply()

            if (serviceBound && cameraService?.isConnected() == true) {
                cameraService?.disconnect()
                binding.connectButton.text = "Connect"
            } else {
                startCameraService(url)
                binding.connectButton.text = "Disconnect"
            }
        } catch (e: Exception) {
            log("Connect error: ${e.message}")
        }
    }

    private fun startCameraService(serverUrl: String) {
        try {
            val intent = Intent(this, CameraService::class.java).apply {
                putExtra("server_url", serverUrl)
                putExtra("device_id", deviceId)
                putExtra("device_name", Build.MODEL)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            log("Starting camera service...")
        } catch (e: Exception) {
            log("Start service error: ${e.message}")
        }
    }

    private fun setupServiceCallbacks() {
        cameraService?.setCallbacks(object : CameraService.ServiceCallbacks {
            override fun onConnectionStatusChanged(connected: Boolean) {
                runOnUiThread {
                    try {
                        if (connected) {
                            binding.connectionIndicator.setBackgroundResource(R.drawable.status_indicator_online)
                            binding.connectionStatusText.text = "Connected"
                            binding.connectionStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                            binding.connectButton.text = "Disconnect"
                        } else {
                            binding.connectionIndicator.setBackgroundResource(R.drawable.status_indicator_offline)
                            binding.connectionStatusText.text = "Disconnected"
                            binding.connectionStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                            binding.connectButton.text = "Connect"
                        }
                    } catch (e: Exception) {
                        log("UI update error: ${e.message}")
                    }
                }
            }

            override fun onRecordingStatusChanged(recording: Boolean) {
                runOnUiThread {
                    try {
                        if (recording) {
                            binding.recordingIndicator.setBackgroundResource(R.drawable.status_indicator_recording)
                            binding.recordingStatusText.text = "Recording"
                            binding.recordingStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                        } else {
                            binding.recordingIndicator.setBackgroundResource(R.drawable.status_indicator_offline)
                            binding.recordingStatusText.text = "Not Recording"
                            binding.recordingStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                        }
                    } catch (e: Exception) {
                        log("Recording UI update error: ${e.message}")
                    }
                }
            }

            override fun onLog(message: String) {
                runOnUiThread { log(message) }
            }

            override fun onPreviewReady(frontPreview: PreviewView?, backPreview: PreviewView?) {
                // Previews are handled in the service
            }
            
            override fun onCameraInfoUpdated(hasFront: Boolean, hasBack: Boolean, preferred: String) {
                runOnUiThread {
                    updateCameraSelector(hasFront, hasBack, preferred)
                }
            }
        })

        cameraService?.bindPreviews(binding.frontPreview, binding.backPreview)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (hasAllPermissions()) {
                    val url = binding.serverUrlInput.text.toString().trim()
                    if (url.isNotEmpty()) {
                        startCameraService(url)
                    }
                } else {
                    log("Permissions required for camera access")
                    
                    // Show which permissions are missing
                    val missing = requiredPermissions.filter {
                        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    log("Missing: ${missing.joinToString { it.substringAfterLast('.') }}")
                }
            }
        } catch (e: Exception) {
            log("Permission result error: ${e.message}")
        }
    }

    private fun obtainDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }

    private fun log(message: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val currentLog = binding.logText.text.toString()
            val newLog = "[$timestamp] $message\n$currentLog"
            binding.logText.text = newLog.take(5000)
            binding.logLayout.post { binding.logLayout.fullScroll(android.view.View.FOCUS_UP) }
        } catch (e: Exception) {
            // Ignore logging errors
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
            }
        } catch (e: Exception) {
            // Ignore unbind errors
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
