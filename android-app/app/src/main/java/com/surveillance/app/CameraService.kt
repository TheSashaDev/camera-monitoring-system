package com.surveillance.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class CameraService : LifecycleService() {

    private val binder = LocalBinder()
    private var callbacks: ServiceCallbacks? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var socket: Socket? = null
    private var serverUrl: String = ""
    private var deviceId: String = ""
    private var deviceName: String = ""

    private var frontPreview: PreviewView? = null
    private var backPreview: PreviewView? = null

    private var frontRecording: Recording? = null
    private var backRecording: Recording? = null
    private var isRecording = false

    private var frontVideoCapture: VideoCapture<Recorder>? = null
    private var backVideoCapture: VideoCapture<Recorder>? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    interface ServiceCallbacks {
        fun onConnectionStatusChanged(connected: Boolean)
        fun onRecordingStatusChanged(recording: Boolean)
        fun onLog(message: String)
        fun onPreviewReady(frontPreview: PreviewView?, backPreview: PreviewView?)
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        intent?.let {
            serverUrl = it.getStringExtra("server_url") ?: ""
            deviceId = it.getStringExtra("device_id") ?: ""
            deviceName = it.getStringExtra("device_name") ?: "Android Device"

            if (serverUrl.isNotEmpty()) {
                connectToServer()
            }
        }

        return START_STICKY
    }

    fun setCallbacks(callbacks: ServiceCallbacks) {
        this.callbacks = callbacks
    }

    fun bindPreviews(front: PreviewView, back: PreviewView) {
        frontPreview = front
        backPreview = back
        setupCameras()
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun disconnect() {
        socket?.disconnect()
        socket = null
        callbacks?.onConnectionStatusChanged(false)
        log("Disconnected from server")
    }

    private fun connectToServer() {
        try {
            log("Connecting to $serverUrl...")

            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 20000
            }

            socket = IO.socket(serverUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                log("Connected to server")
                callbacks?.onConnectionStatusChanged(true)
                updateNotification("Connected")

                val data = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("deviceName", deviceName)
                    put("cameras", JSONArray().apply {
                        put("front")
                        put("back")
                    })
                }
                socket?.emit("device:register", data)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                log("Disconnected from server")
                callbacks?.onConnectionStatusChanged(false)
                updateNotification("Disconnected")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args.firstOrNull()?.toString() ?: "Unknown error"
                log("Connection error: $error")
            }

            socket?.on("device:registered") {
                log("Device registered successfully")
            }

            socket?.on("command:start_recording") { args ->
                val data = args.firstOrNull() as? JSONObject
                val cameras = data?.optJSONArray("cameras")
                log("Received start recording command")
                startRecording()
            }

            socket?.on("command:stop_recording") {
                log("Received stop recording command")
                stopRecording()
            }

            socket?.on("webrtc:offer") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                handleWebRTCOffer(data)
            }

            socket?.on("webrtc:ice_candidate") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                handleICECandidate(data)
            }

            socket?.on("webrtc:stop") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                handleWebRTCStop(data)
            }

            socket?.connect()

        } catch (e: Exception) {
            log("Failed to connect: ${e.message}")
            Log.e(TAG, "Connection error", e)
        }
    }

    private fun setupCameras() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                val frontRecorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                frontVideoCapture = VideoCapture.withOutput(frontRecorder)

                val backRecorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                backVideoCapture = VideoCapture.withOutput(backRecorder)

                frontPreview?.let { preview ->
                    val frontPreviewUseCase = Preview.Builder().build()
                    frontPreviewUseCase.setSurfaceProvider(preview.surfaceProvider)
                    
                    try {
                        cameraProvider.unbind(frontPreviewUseCase)
                        cameraProvider.bindToLifecycle(
                            this,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            frontPreviewUseCase,
                            frontVideoCapture
                        )
                        log("Front camera initialized")
                    } catch (e: Exception) {
                        log("Front camera error: ${e.message}")
                    }
                }

                backPreview?.let { preview ->
                    val backPreviewUseCase = Preview.Builder().build()
                    backPreviewUseCase.setSurfaceProvider(preview.surfaceProvider)
                    
                    try {
                        cameraProvider.bindToLifecycle(
                            this,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            backPreviewUseCase,
                            backVideoCapture
                        )
                        log("Back camera initialized")
                    } catch (e: Exception) {
                        log("Back camera error: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                log("Camera setup error: ${e.message}")
                Log.e(TAG, "Camera setup error", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        if (isRecording) {
            log("Already recording")
            return
        }

        isRecording = true
        callbacks?.onRecordingStatusChanged(true)
        updateNotification("Recording...")
        notifyRecordingStatus(true)

        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        frontVideoCapture?.let { videoCapture ->
            val file = File(recordingsDir, "front_$timestamp.mp4")
            val outputOptions = FileOutputOptions.Builder(file).build()

            frontRecording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(cameraExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> log("Front camera recording started")
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                log("Front recording error: ${event.error}")
                            } else {
                                log("Front recording saved: ${file.name}")
                                uploadRecording(file, "front", timestamp)
                            }
                        }
                    }
                }
        }

        backVideoCapture?.let { videoCapture ->
            val file = File(recordingsDir, "back_$timestamp.mp4")
            val outputOptions = FileOutputOptions.Builder(file).build()

            backRecording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(cameraExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> log("Back camera recording started")
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                log("Back recording error: ${event.error}")
                            } else {
                                log("Back recording saved: ${file.name}")
                                uploadRecording(file, "back", timestamp)
                            }
                        }
                    }
                }
        }

        log("Recording started on both cameras")
    }

    private fun stopRecording() {
        if (!isRecording) {
            log("Not recording")
            return
        }

        frontRecording?.stop()
        frontRecording = null

        backRecording?.stop()
        backRecording = null

        isRecording = false
        callbacks?.onRecordingStatusChanged(false)
        updateNotification("Connected")
        notifyRecordingStatus(false)

        log("Recording stopped")
    }

    private fun uploadRecording(file: File, cameraType: String, startedAt: Long) {
        scope.launch {
            try {
                val endedAt = System.currentTimeMillis()
                val duration = ((endedAt - startedAt) / 1000).toInt()

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaType()))
                    .addFormDataPart("cameraType", cameraType)
                    .addFormDataPart("duration", duration.toString())
                    .addFormDataPart("startedAt", (startedAt / 1000).toString())
                    .addFormDataPart("endedAt", (endedAt / 1000).toString())
                    .build()

                val request = Request.Builder()
                    .url("$serverUrl/api/recordings/$deviceId")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        log("Uploaded: ${file.name}")
                        file.delete()
                    } else {
                        log("Upload failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                log("Upload error: ${e.message}")
                Log.e(TAG, "Upload error", e)
            }
        }
    }

    private fun notifyRecordingStatus(recording: Boolean) {
        socket?.emit("device:recording_status", JSONObject().apply {
            put("isRecording", recording)
        })
    }

    private fun handleWebRTCOffer(data: JSONObject) {
        // WebRTC implementation would go here
        // For simplicity, this is a placeholder
        log("WebRTC offer received from admin")
    }

    private fun handleICECandidate(data: JSONObject) {
        log("ICE candidate received")
    }

    private fun handleWebRTCStop(data: JSONObject) {
        log("WebRTC stop received")
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraSurveillance::WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Camera Surveillance")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        callbacks?.onLog(message)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        socket?.disconnect()
        wakeLock?.release()
        cameraExecutor.shutdown()
        scope.cancel()
    }

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "camera_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}
