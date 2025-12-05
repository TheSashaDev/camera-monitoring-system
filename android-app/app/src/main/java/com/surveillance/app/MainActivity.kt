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
import android.view.WindowManager
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
    private val deviceId: String by lazy { getDeviceId() }

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
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            serviceBound = true
            setupServiceCallbacks()
            log("Service bound")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            serviceBound = false
            log("Service unbound")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
            .getString("server_url", "http://192.168.1.100:3000") ?: ""
        binding.serverUrlInput.setText(savedUrl)

        binding.connectButton.setOnClickListener {
            if (!hasAllPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            val url = binding.serverUrlInput.text.toString().trim()
            if (url.isEmpty()) {
                log("Please enter server URL")
                return@setOnClickListener
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
        }

        if (hasAllPermissions()) {
            startCameraService(savedUrl)
        } else {
            requestPermissions()
        }
    }

    private fun startCameraService(serverUrl: String) {
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
    }

    private fun setupServiceCallbacks() {
        cameraService?.setCallbacks(object : CameraService.ServiceCallbacks {
            override fun onConnectionStatusChanged(connected: Boolean) {
                runOnUiThread {
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
                }
            }

            override fun onRecordingStatusChanged(recording: Boolean) {
                runOnUiThread {
                    if (recording) {
                        binding.recordingIndicator.setBackgroundResource(R.drawable.status_indicator_recording)
                        binding.recordingStatusText.text = "Recording"
                        binding.recordingStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_light))
                    } else {
                        binding.recordingIndicator.setBackgroundResource(R.drawable.status_indicator_offline)
                        binding.recordingStatusText.text = "Not Recording"
                        binding.recordingStatusText.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
                    }
                }
            }

            override fun onLog(message: String) {
                runOnUiThread { log(message) }
            }

            override fun onPreviewReady(frontPreview: PreviewView?, backPreview: PreviewView?) {
                // Previews are handled in the service
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
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasAllPermissions()) {
                val url = binding.serverUrlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    startCameraService(url)
                }
            } else {
                log("Permissions required for camera access")
            }
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) 
            ?: UUID.randomUUID().toString()
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLog = binding.logText.text.toString()
        val newLog = "[$timestamp] $message\n$currentLog"
        binding.logText.text = newLog.take(5000)
        binding.logLayout.post { binding.logLayout.fullScroll(android.view.View.FOCUS_UP) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }
}
