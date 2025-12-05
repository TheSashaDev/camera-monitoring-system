package com.surveillance.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Size
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
import org.webrtc.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private var isRecording = AtomicBoolean(false)
    private var recordingStartTime: Long = 0

    private var frontVideoCapture: VideoCapture<Recorder>? = null
    private var backVideoCapture: VideoCapture<Recorder>? = null
    
    private var hasFrontCamera = false
    private var hasBackCamera = false
    private var preferredCamera: String = "auto" // "front", "back", or "auto"
    private var actualRecordingCamera: String = "back" // Which camera is actually being used

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val uploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val CHUNK_DURATION_MS = 30 * 1000L // 30 seconds - fast upload chunks
    private var chunkHandler: Handler? = null
    private var chunkRunnable: Runnable? = null

    // WebRTC
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private var eglBase: EglBase? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Retry and stability
    private var reconnectAttempts = 0
    private val MAX_RECONNECT_ATTEMPTS = 100
    private val uploadRetryQueue = mutableListOf<UploadTask>()
    private var isUploading = AtomicBoolean(false)
    
    private lateinit var prefs: SharedPreferences

    data class UploadTask(
        val file: File,
        val cameraType: String,
        val startedAt: Long,
        var retryCount: Int = 0
    )

    interface ServiceCallbacks {
        fun onConnectionStatusChanged(connected: Boolean)
        fun onRecordingStatusChanged(recording: Boolean)
        fun onLog(message: String)
        fun onPreviewReady(frontPreview: PreviewView?, backPreview: PreviewView?)
        fun onCameraInfoUpdated(hasFront: Boolean, hasBack: Boolean, preferred: String)
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
        prefs = getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
        preferredCamera = prefs.getString("preferred_camera", "auto") ?: "auto"
        
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        detectAvailableCameras()
        initWebRTC()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        try {
            intent?.let {
                serverUrl = it.getStringExtra("server_url") ?: ""
                deviceId = it.getStringExtra("device_id") ?: ""
                deviceName = it.getStringExtra("device_name") ?: "Android Device"

                if (serverUrl.isNotEmpty()) {
                    connectToServer()
                } else {
                    log("Error: Server URL is empty")
                }
            }
        } catch (e: Exception) {
            log("StartCommand error: ${e.message}")
            Log.e(TAG, "onStartCommand error", e)
        }

        return START_STICKY
    }

    fun setCallbacks(callbacks: ServiceCallbacks) {
        this.callbacks = callbacks
        callbacks.onCameraInfoUpdated(hasFrontCamera, hasBackCamera, preferredCamera)
    }

    fun bindPreviews(front: PreviewView, back: PreviewView) {
        frontPreview = front
        backPreview = back
        setupCameras()
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun disconnect() {
        try {
            socket?.disconnect()
            socket = null
            callbacks?.onConnectionStatusChanged(false)
            log("Disconnected from server")
        } catch (e: Exception) {
            log("Disconnect error: ${e.message}")
        }
    }
    
    fun setPreferredCamera(camera: String) {
        preferredCamera = camera
        prefs.edit().putString("preferred_camera", camera).apply()
        log("Preferred camera set to: $camera")
        
        // If recording, restart with new camera preference
        if (isRecording.get()) {
            log("Restarting recording with new camera preference...")
            mainHandler.post {
                stopRecording()
                startRecording()
            }
        }
    }
    
    fun getPreferredCamera(): String = preferredCamera
    
    fun getAvailableCameras(): Pair<Boolean, Boolean> = Pair(hasFrontCamera, hasBackCamera)
    
    private fun detectAvailableCameras() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            
            for (cameraId in cameraIds) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                
                when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        hasFrontCamera = true
                        log("Front camera detected: $cameraId")
                    }
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        hasBackCamera = true
                        log("Back camera detected: $cameraId")
                    }
                }
            }
            
            // Auto-select best camera based on availability
            actualRecordingCamera = when {
                preferredCamera == "front" && hasFrontCamera -> "front"
                preferredCamera == "back" && hasBackCamera -> "back"
                preferredCamera == "auto" -> {
                    // Prefer back camera for surveillance as it usually has better quality
                    when {
                        hasBackCamera -> "back"
                        hasFrontCamera -> "front"
                        else -> "none"
                    }
                }
                // Fallback if preferred camera not available
                hasBackCamera -> "back"
                hasFrontCamera -> "front"
                else -> "none"
            }
            
            log("Camera detection: front=$hasFrontCamera, back=$hasBackCamera, selected=$actualRecordingCamera")
            
            if (!hasFrontCamera && !hasBackCamera) {
                log("WARNING: No cameras detected!")
            }
            
        } catch (e: Exception) {
            log("Camera detection error: ${e.message}")
            Log.e(TAG, "Camera detection error", e)
            // Default to back camera
            hasBackCamera = true
            actualRecordingCamera = "back"
        }
    }

    private fun initWebRTC() {
        try {
            eglBase = EglBase.create()

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                .createPeerConnectionFactory()

            log("WebRTC initialized")
        } catch (e: Exception) {
            log("WebRTC init error: ${e.message}")
            Log.e(TAG, "WebRTC init error", e)
        }
    }

    private fun connectToServer() {
        try {
            log("Connecting to $serverUrl...")

            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = MAX_RECONNECT_ATTEMPTS
                reconnectionDelay = 1000
                reconnectionDelayMax = 10000
                timeout = 30000
            }

            socket = IO.socket(serverUrl, options)

            socket?.on(Socket.EVENT_CONNECT) {
                reconnectAttempts = 0
                log("Connected to server")
                callbacks?.onConnectionStatusChanged(true)
                updateNotification("Connected")

                try {
                    val availableCameras = JSONArray().apply {
                        if (hasFrontCamera) put("front")
                        if (hasBackCamera) put("back")
                    }
                    
                    val data = JSONObject().apply {
                        put("deviceId", deviceId)
                        put("deviceName", deviceName)
                        put("cameras", availableCameras)
                        put("preferredCamera", actualRecordingCamera)
                    }
                    socket?.emit("device:register", data)
                } catch (e: Exception) {
                    log("Registration data error: ${e.message}")
                }
            }

            socket?.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "Unknown"
                log("Disconnected from server: $reason")
                callbacks?.onConnectionStatusChanged(false)
                updateNotification("Disconnected")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                reconnectAttempts++
                val error = args.firstOrNull()?.toString() ?: "Unknown error"
                log("Connection error ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS): $error")
                
                if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    log("Max reconnection attempts reached. Will keep trying...")
                    reconnectAttempts = 0
                }
            }

            socket?.on("device:registered") {
                log("Device registered successfully")
            }

            socket?.on("device:error") { args ->
                val data = args.firstOrNull() as? JSONObject
                val error = data?.optString("error", "Unknown error") ?: "Unknown error"
                log("Device error: $error")
            }

            socket?.on("command:start_recording") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject
                    val cameras = data?.optJSONArray("cameras")
                    log("Received start recording command")
                    mainHandler.post { startRecording() }
                } catch (e: Exception) {
                    log("Start recording command error: ${e.message}")
                }
            }

            socket?.on("command:stop_recording") {
                log("Received stop recording command")
                mainHandler.post { stopRecording() }
            }

            // WebRTC signaling
            socket?.on("webrtc:offer") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    mainHandler.post { handleWebRTCOffer(data) }
                } catch (e: Exception) {
                    log("WebRTC offer parse error: ${e.message}")
                }
            }

            socket?.on("webrtc:ice_candidate") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    mainHandler.post { handleICECandidate(data) }
                } catch (e: Exception) {
                    log("ICE candidate parse error: ${e.message}")
                }
            }

            socket?.on("webrtc:stop") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject ?: return@on
                    mainHandler.post { handleWebRTCStop(data) }
                } catch (e: Exception) {
                    log("WebRTC stop parse error: ${e.message}")
                }
            }

            socket?.connect()

        } catch (e: Exception) {
            log("Connection setup error: ${e.message}")
            Log.e(TAG, "Connection error", e)
        }
    }

    private fun setupCameras() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Unbind all before rebinding
                cameraProvider.unbindAll()

                val qualitySelector = QualitySelector.from(
                    Quality.HD,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )

                // Determine which camera to use
                val useCamera = when {
                    actualRecordingCamera == "front" && hasFrontCamera -> "front"
                    actualRecordingCamera == "back" && hasBackCamera -> "back"
                    hasBackCamera -> "back"
                    hasFrontCamera -> "front"
                    else -> "none"
                }
                
                log("Setting up camera: $useCamera")

                when (useCamera) {
                    "front" -> {
                        try {
                            val recorder = Recorder.Builder()
                                .setQualitySelector(qualitySelector)
                                .build()
                            frontVideoCapture = VideoCapture.withOutput(recorder)

                            frontPreview?.let { preview ->
                                val previewUseCase = Preview.Builder().build()
                                previewUseCase.setSurfaceProvider(preview.surfaceProvider)

                                cameraProvider.bindToLifecycle(
                                    this,
                                    CameraSelector.DEFAULT_FRONT_CAMERA,
                                    previewUseCase,
                                    frontVideoCapture
                                )
                                log("Front camera initialized successfully")
                            }
                        } catch (e: Exception) {
                            hasFrontCamera = false
                            log("Front camera init error: ${e.message}")
                            Log.e(TAG, "Front camera error", e)
                        }
                    }
                    "back" -> {
                        try {
                            val recorder = Recorder.Builder()
                                .setQualitySelector(qualitySelector)
                                .build()
                            backVideoCapture = VideoCapture.withOutput(recorder)

                            backPreview?.let { preview ->
                                val previewUseCase = Preview.Builder().build()
                                previewUseCase.setSurfaceProvider(preview.surfaceProvider)

                                cameraProvider.bindToLifecycle(
                                    this,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    previewUseCase,
                                    backVideoCapture
                                )
                                log("Back camera initialized successfully")
                            }
                        } catch (e: Exception) {
                            hasBackCamera = false
                            log("Back camera init error: ${e.message}")
                            Log.e(TAG, "Back camera error", e)
                        }
                    }
                    else -> {
                        log("No camera available to setup")
                    }
                }
                
                actualRecordingCamera = useCamera
                callbacks?.onCameraInfoUpdated(hasFrontCamera, hasBackCamera, preferredCamera)

            } catch (e: Exception) {
                log("Camera setup error: ${e.message}")
                Log.e(TAG, "Camera setup error", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        if (isRecording.getAndSet(true)) {
            log("Already recording")
            return
        }

        // Re-check camera availability
        detectAvailableCameras()
        
        if (actualRecordingCamera == "none") {
            log("ERROR: No camera available for recording!")
            isRecording.set(false)
            return
        }

        recordingStartTime = System.currentTimeMillis()
        callbacks?.onRecordingStatusChanged(true)
        updateNotification("Recording ($actualRecordingCamera camera)...")
        notifyRecordingStatus(true)

        startRecordingChunk()
        scheduleNextChunk()

        log("Recording started using $actualRecordingCamera camera (30-sec fast chunks)")
    }

    private fun startRecordingChunk() {
        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        try {
            // Record based on selected camera
            when (actualRecordingCamera) {
                "front" -> startFrontRecording(recordingsDir, timestamp)
                "back" -> startBackRecording(recordingsDir, timestamp)
                "both" -> {
                    startFrontRecording(recordingsDir, timestamp)
                    startBackRecording(recordingsDir, timestamp)
                }
            }
        } catch (e: Exception) {
            log("Recording chunk start error: ${e.message}")
            Log.e(TAG, "Recording chunk error", e)
        }
    }
    
    private fun startFrontRecording(recordingsDir: File, timestamp: Long) {
        if (!hasFrontCamera || frontVideoCapture == null) {
            log("Front camera not available for recording")
            return
        }
        
        try {
            val file = File(recordingsDir, "front_$timestamp.mp4")
            val outputOptions = FileOutputOptions.Builder(file).build()

            frontRecording = frontVideoCapture!!.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(cameraExecutor) { event ->
                    handleRecordingEvent(event, file, "front", timestamp)
                }
            log("Front camera recording started")
        } catch (e: Exception) {
            log("Front recording start error: ${e.message}")
        }
    }
    
    private fun startBackRecording(recordingsDir: File, timestamp: Long) {
        if (!hasBackCamera || backVideoCapture == null) {
            log("Back camera not available for recording")
            return
        }
        
        try {
            val file = File(recordingsDir, "back_$timestamp.mp4")
            val outputOptions = FileOutputOptions.Builder(file).build()

            backRecording = backVideoCapture!!.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(cameraExecutor) { event ->
                    handleRecordingEvent(event, file, "back", timestamp)
                }
            log("Back camera recording started")
        } catch (e: Exception) {
            log("Back recording start error: ${e.message}")
        }
    }
    
    private fun handleRecordingEvent(event: VideoRecordEvent, file: File, cameraType: String, timestamp: Long) {
        when (event) {
            is VideoRecordEvent.Start -> log("$cameraType camera chunk started")
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    log("$cameraType recording error (code ${event.error}): ${event.cause?.message}")
                    try { file.delete() } catch (e: Exception) { }
                } else {
                    val sizeKB = file.length() / 1024
                    log("$cameraType chunk saved: ${file.name} (${sizeKB}KB)")
                    queueUpload(file, cameraType, timestamp)
                }
            }
            is VideoRecordEvent.Status -> {
                // Optional: log recording progress
            }
        }
    }

    private fun scheduleNextChunk() {
        chunkHandler = Handler(Looper.getMainLooper())
        chunkRunnable = Runnable {
            if (isRecording.get()) {
                log("Starting new chunk...")
                try {
                    frontRecording?.stop()
                    backRecording?.stop()
                    frontRecording = null
                    backRecording = null

                    startRecordingChunk()
                    scheduleNextChunk()
                } catch (e: Exception) {
                    log("Chunk rotation error: ${e.message}")
                }
            }
        }
        chunkHandler?.postDelayed(chunkRunnable!!, CHUNK_DURATION_MS)
    }

    private fun stopRecording() {
        if (!isRecording.getAndSet(false)) {
            log("Not recording")
            return
        }

        try {
            chunkRunnable?.let { chunkHandler?.removeCallbacks(it) }
            chunkHandler = null
            chunkRunnable = null

            frontRecording?.stop()
            frontRecording = null

            backRecording?.stop()
            backRecording = null

            callbacks?.onRecordingStatusChanged(false)
            updateNotification("Connected")
            notifyRecordingStatus(false)

            log("Recording stopped")
        } catch (e: Exception) {
            log("Stop recording error: ${e.message}")
        }
    }
    
    private fun queueUpload(file: File, cameraType: String, startedAt: Long) {
        // Immediate parallel upload - don't queue, just upload directly
        uploadScope.launch {
            uploadRecordingImmediate(file, cameraType, startedAt)
        }
    }
    
    private suspend fun uploadRecordingImmediate(file: File, cameraType: String, startedAt: Long, retryCount: Int = 0) {
        val maxRetries = 3
        
        try {
            val endedAt = System.currentTimeMillis()
            val duration = ((endedAt - startedAt) / 1000).toInt()
            val fileSizeKB = file.length() / 1024

            log("Uploading ${file.name} (${fileSizeKB}KB)...")

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

            val success = httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    log("Uploaded: ${file.name}")
                    true
                } else {
                    log("Upload failed (${response.code}): ${file.name}")
                    false
                }
            }
            
            if (success) {
                if (file.delete()) {
                    log("Deleted local: ${file.name}")
                }
            } else if (retryCount < maxRetries) {
                delay(5000) // Short retry delay
                uploadRecordingImmediate(file, cameraType, startedAt, retryCount + 1)
            } else {
                log("Upload failed after $maxRetries attempts: ${file.name}")
                // Keep file for manual recovery
            }
        } catch (e: Exception) {
            log("Upload error: ${e.message}")
            if (retryCount < maxRetries) {
                delay(5000)
                uploadRecordingImmediate(file, cameraType, startedAt, retryCount + 1)
            }
        }
    }
    
    // Keep old method for compatibility but unused
    private fun processUploadQueue() {
        // Deprecated - using immediate uploads now
    }

    private suspend fun uploadRecording(task: UploadTask): Boolean {
        return try {
            val endedAt = System.currentTimeMillis()
            val duration = ((endedAt - task.startedAt) / 1000).toInt()

            log("Uploading ${task.file.name} (attempt ${task.retryCount + 1})...")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("video", task.file.name, task.file.asRequestBody("video/mp4".toMediaType()))
                .addFormDataPart("cameraType", task.cameraType)
                .addFormDataPart("duration", duration.toString())
                .addFormDataPart("startedAt", (task.startedAt / 1000).toString())
                .addFormDataPart("endedAt", (endedAt / 1000).toString())
                .build()

            val request = Request.Builder()
                .url("$serverUrl/api/recordings/$deviceId")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    log("Uploaded: ${task.file.name}")
                    if (task.file.delete()) {
                        log("Deleted local: ${task.file.name}")
                    }
                    true
                } else {
                    log("Upload failed (${response.code}): ${task.file.name}")
                    false
                }
            }
        } catch (e: Exception) {
            log("Upload error: ${e.message}")
            Log.e(TAG, "Upload error", e)
            false
        }
    }

    private fun notifyRecordingStatus(recording: Boolean) {
        try {
            socket?.emit("device:recording_status", JSONObject().apply {
                put("isRecording", recording)
                put("camera", actualRecordingCamera)
            })
        } catch (e: Exception) {
            log("Status notify error: ${e.message}")
        }
    }

    // WebRTC handling
    private fun handleWebRTCOffer(data: JSONObject) {
        try {
            val adminSocketId = data.getString("adminSocketId")
            val offerJson = data.getJSONObject("offer")
            val requestedCamera = data.optString("cameraType", "back")
            
            // Use requested camera if available, otherwise use actual recording camera
            val cameraType = when {
                requestedCamera == "front" && hasFrontCamera -> "front"
                requestedCamera == "back" && hasBackCamera -> "back"
                hasBackCamera -> "back"
                hasFrontCamera -> "front"
                else -> {
                    log("No camera available for WebRTC")
                    return
                }
            }

            log("WebRTC offer from admin for $cameraType camera")

            val offer = SessionDescription(
                SessionDescription.Type.OFFER,
                offerJson.getString("sdp")
            )

            createPeerConnection(adminSocketId, cameraType)?.let { pc ->
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        createAnswer(pc, adminSocketId, cameraType)
                    }
                    override fun onSetFailure(error: String?) {
                        log("Set remote description failed: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, offer)
            }
        } catch (e: Exception) {
            log("WebRTC offer error: ${e.message}")
            Log.e(TAG, "WebRTC offer error", e)
        }
    }

    private fun createPeerConnection(adminSocketId: String, cameraType: String): PeerConnection? {
        val key = "$adminSocketId-$cameraType"

        try {
            peerConnections[key]?.close()

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                // Free TURN servers for NAT traversal
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer(),
                PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
                    .setUsername("openrelayproject")
                    .setPassword("openrelayproject")
                    .createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        try {
                            socket?.emit("webrtc:ice_candidate", JSONObject().apply {
                                put("targetId", adminSocketId)
                                put("cameraType", cameraType)
                                put("candidate", JSONObject().apply {
                                    put("sdpMid", it.sdpMid)
                                    put("sdpMLineIndex", it.sdpMLineIndex)
                                    put("candidate", it.sdp)
                                })
                            })
                        } catch (e: Exception) {
                            log("ICE candidate emit error: ${e.message}")
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    log("ICE connection state: $state")
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED) {
                        peerConnections.remove(key)?.close()
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            })

            pc?.let {
                createVideoTrack(cameraType)?.let { track ->
                    it.addTrack(track)
                }
                createAudioTrack()?.let { track ->
                    it.addTrack(track)
                }
                peerConnections[key] = it
            }

            return pc
        } catch (e: Exception) {
            log("Create peer connection error: ${e.message}")
            return null
        }
    }

    private fun createVideoTrack(cameraType: String): VideoTrack? {
        try {
            val videoSource = peerConnectionFactory?.createVideoSource(false)

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)

            val cameraEnumerator = Camera2Enumerator(this)
            val deviceNames = cameraEnumerator.deviceNames

            val targetCamera = when (cameraType) {
                "front" -> deviceNames.find { cameraEnumerator.isFrontFacing(it) }
                else -> deviceNames.find { cameraEnumerator.isBackFacing(it) }
            } ?: deviceNames.firstOrNull()

            targetCamera?.let { cameraName ->
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                
                videoCapturer = cameraEnumerator.createCapturer(cameraName, object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(errorDescription: String?) {
                        log("WebRTC camera error: $errorDescription")
                    }
                    override fun onCameraDisconnected() {
                        log("WebRTC camera disconnected")
                    }
                    override fun onCameraFreezed(errorDescription: String?) {
                        log("WebRTC camera frozen: $errorDescription")
                    }
                    override fun onCameraOpening(cameraName: String?) {}
                    override fun onFirstFrameAvailable() {}
                    override fun onCameraClosed() {}
                })
                
                videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
                videoCapturer?.startCapture(1280, 720, 30)
            } ?: run {
                log("No camera found for WebRTC: $cameraType")
            }

            localVideoTrack = peerConnectionFactory?.createVideoTrack("video_$cameraType", videoSource)
            return localVideoTrack
        } catch (e: Exception) {
            log("Create video track error: ${e.message}")
            return null
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        try {
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio", audioSource)
            return localAudioTrack
        } catch (e: Exception) {
            log("Create audio track error: ${e.message}")
            return null
        }
    }

    private fun createAnswer(pc: PeerConnection, adminSocketId: String, cameraType: String) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            try {
                                socket?.emit("webrtc:answer", JSONObject().apply {
                                    put("adminSocketId", adminSocketId)
                                    put("cameraType", cameraType)
                                    put("answer", JSONObject().apply {
                                        put("type", "answer")
                                        put("sdp", it.description)
                                    })
                                })
                                log("WebRTC answer sent for $cameraType")
                            } catch (e: Exception) {
                                log("WebRTC answer emit error: ${e.message}")
                            }
                        }
                        override fun onSetFailure(error: String?) {
                            log("Set local description failed: $error")
                        }
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, it)
                }
            }
            override fun onCreateFailure(error: String?) {
                log("Create answer failed: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    private fun handleICECandidate(data: JSONObject) {
        try {
            val adminSocketId = data.getString("adminSocketId")
            val cameraType = data.optString("cameraType", "back")
            val candidateJson = data.getJSONObject("candidate")

            val candidate = IceCandidate(
                candidateJson.getString("sdpMid"),
                candidateJson.getInt("sdpMLineIndex"),
                candidateJson.getString("candidate")
            )

            val key = "$adminSocketId-$cameraType"
            peerConnections[key]?.addIceCandidate(candidate)
        } catch (e: Exception) {
            log("ICE candidate error: ${e.message}")
        }
    }

    private fun handleWebRTCStop(data: JSONObject) {
        try {
            val adminSocketId = data.getString("adminSocketId")
            val cameraType = data.optString("cameraType", "back")
            val key = "$adminSocketId-$cameraType"

            peerConnections.remove(key)?.close()
            log("WebRTC stopped for $cameraType")
        } catch (e: Exception) {
            log("WebRTC stop error: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CameraSurveillance::WakeLock"
            )
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            log("WakeLock error: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_description)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "Notification channel error", e)
            }
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
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, createNotification(status))
        } catch (e: Exception) {
            Log.e(TAG, "Notification update error", e)
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        mainHandler.post { callbacks?.onLog(message) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopRecording()
            socket?.disconnect()

            peerConnections.values.forEach { 
                try { it.close() } catch (e: Exception) { }
            }
            peerConnections.clear()
            
            try { videoCapturer?.stopCapture() } catch (e: Exception) { }
            try { videoCapturer?.dispose() } catch (e: Exception) { }
            try { surfaceTextureHelper?.dispose() } catch (e: Exception) { }
            try { peerConnectionFactory?.dispose() } catch (e: Exception) { }
            try { eglBase?.release() } catch (e: Exception) { }

            try { wakeLock?.release() } catch (e: Exception) { }
            cameraExecutor.shutdown()
            scope.cancel()
            uploadScope.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Destroy error", e)
        }
    }

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "camera_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}
