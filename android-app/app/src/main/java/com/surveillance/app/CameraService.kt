package com.surveillance.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import org.webrtc.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var recordingStartTime: Long = 0

    private var frontVideoCapture: VideoCapture<Recorder>? = null
    private var backVideoCapture: VideoCapture<Recorder>? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Recording chunk settings
    private val CHUNK_DURATION_MS = 15 * 60 * 1000L // 15 minutes
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
        initWebRTC()
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
                log("Received start recording command")
                mainHandler.post { startRecording() }
            }

            socket?.on("command:stop_recording") {
                log("Received stop recording command")
                mainHandler.post { stopRecording() }
            }

            // WebRTC signaling
            socket?.on("webrtc:offer") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                mainHandler.post { handleWebRTCOffer(data) }
            }

            socket?.on("webrtc:ice_candidate") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                mainHandler.post { handleICECandidate(data) }
            }

            socket?.on("webrtc:stop") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                mainHandler.post { handleWebRTCStop(data) }
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

                // Configure recorder with compression (lower bitrate for smaller files)
                val qualitySelector = QualitySelector.from(
                    Quality.HD, // 720p for good balance
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )

                val frontRecorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                frontVideoCapture = VideoCapture.withOutput(frontRecorder)

                val backRecorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()
                backVideoCapture = VideoCapture.withOutput(backRecorder)

                frontPreview?.let { preview ->
                    val frontPreviewUseCase = Preview.Builder().build()
                    frontPreviewUseCase.setSurfaceProvider(preview.surfaceProvider)

                    try {
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
        recordingStartTime = System.currentTimeMillis()
        callbacks?.onRecordingStatusChanged(true)
        updateNotification("Recording...")
        notifyRecordingStatus(true)

        startRecordingChunk()
        scheduleNextChunk()

        log("Recording started (15-min chunks, both cameras with audio)")
    }

    private fun startRecordingChunk() {
        val recordingsDir = File(filesDir, "recordings").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        // Front camera recording with audio
        frontVideoCapture?.let { videoCapture ->
            val file = File(recordingsDir, "front_$timestamp.mp4")
            val outputOptions = FileOutputOptions.Builder(file).build()

            frontRecording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled() // Audio enabled
                .start(cameraExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> log("Front camera chunk started")
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                log("Front recording error: ${event.error}")
                                file.delete()
                            } else {
                                log("Front chunk saved: ${file.name} (${file.length() / 1024}KB)")
                                uploadAndDeleteRecording(file, "front", timestamp)
                            }
                        }
                    }
                }
        }

        // Back camera recording with audio
        backVideoCapture?.let { videoCapture ->
            val file = File(recordingsDir, "back_$timestamp.mp4")
            val outputOptions = FileOutputOptions.Builder(file).build()

            backRecording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled() // Audio enabled
                .start(cameraExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> log("Back camera chunk started")
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                log("Back recording error: ${event.error}")
                                file.delete()
                            } else {
                                log("Back chunk saved: ${file.name} (${file.length() / 1024}KB)")
                                uploadAndDeleteRecording(file, "back", timestamp)
                            }
                        }
                    }
                }
        }
    }

    private fun scheduleNextChunk() {
        chunkHandler = Handler(Looper.getMainLooper())
        chunkRunnable = Runnable {
            if (isRecording) {
                log("Starting new 15-minute chunk...")
                // Stop current recordings
                frontRecording?.stop()
                backRecording?.stop()
                frontRecording = null
                backRecording = null

                // Start new chunk
                startRecordingChunk()
                scheduleNextChunk()
            }
        }
        chunkHandler?.postDelayed(chunkRunnable!!, CHUNK_DURATION_MS)
    }

    private fun stopRecording() {
        if (!isRecording) {
            log("Not recording")
            return
        }

        // Cancel scheduled chunk
        chunkRunnable?.let { chunkHandler?.removeCallbacks(it) }
        chunkHandler = null
        chunkRunnable = null

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

    private fun uploadAndDeleteRecording(file: File, cameraType: String, startedAt: Long) {
        scope.launch {
            try {
                val endedAt = System.currentTimeMillis()
                val duration = ((endedAt - startedAt) / 1000).toInt()

                log("Uploading ${file.name}...")

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
                        // Delete local file after successful upload
                        if (file.delete()) {
                            log("Deleted local: ${file.name}")
                        }
                    } else {
                        log("Upload failed (${response.code}): ${file.name}")
                        // Retry upload later - keep file
                        retryUpload(file, cameraType, startedAt)
                    }
                }
            } catch (e: Exception) {
                log("Upload error: ${e.message}")
                Log.e(TAG, "Upload error", e)
                // Retry upload later
                retryUpload(file, cameraType, startedAt)
            }
        }
    }

    private fun retryUpload(file: File, cameraType: String, startedAt: Long) {
        scope.launch {
            delay(30000) // Wait 30 seconds before retry
            if (file.exists()) {
                log("Retrying upload: ${file.name}")
                uploadAndDeleteRecording(file, cameraType, startedAt)
            }
        }
    }

    private fun notifyRecordingStatus(recording: Boolean) {
        socket?.emit("device:recording_status", JSONObject().apply {
            put("isRecording", recording)
        })
    }

    // WebRTC handling
    private fun handleWebRTCOffer(data: JSONObject) {
        try {
            val adminSocketId = data.getString("adminSocketId")
            val offerJson = data.getJSONObject("offer")
            val cameraType = data.optString("cameraType", "back")

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

        // Close existing connection
        peerConnections[key]?.close()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    socket?.emit("webrtc:ice_candidate", JSONObject().apply {
                        put("targetId", adminSocketId)
                        put("cameraType", cameraType)
                        put("candidate", JSONObject().apply {
                            put("sdpMid", it.sdpMid)
                            put("sdpMLineIndex", it.sdpMLineIndex)
                            put("candidate", it.sdp)
                        })
                    })
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
            // Add video track
            createVideoTrack(cameraType)?.let { track ->
                it.addTrack(track)
            }
            // Add audio track
            createAudioTrack()?.let { track ->
                it.addTrack(track)
            }
            peerConnections[key] = it
        }

        return pc
    }

    private fun createVideoTrack(cameraType: String): VideoTrack? {
        try {
            val videoSource = peerConnectionFactory?.createVideoSource(false)

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)

            val cameraEnumerator = Camera2Enumerator(this)
            val deviceNames = cameraEnumerator.deviceNames

            val targetCamera = if (cameraType == "front") {
                deviceNames.find { cameraEnumerator.isFrontFacing(it) }
            } else {
                deviceNames.find { cameraEnumerator.isBackFacing(it) }
            }

            targetCamera?.let { cameraName ->
                videoCapturer = cameraEnumerator.createCapturer(cameraName, null)
                videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
                videoCapturer?.startCapture(1280, 720, 30)
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
                            socket?.emit("webrtc:answer", JSONObject().apply {
                                put("adminSocketId", adminSocketId)
                                put("cameraType", cameraType)
                                put("answer", JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", it.description)
                                })
                            })
                            log("WebRTC answer sent for $cameraType")
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
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CameraSurveillance::WakeLock"
        )
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours
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
        mainHandler.post { callbacks?.onLog(message) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        socket?.disconnect()

        // Cleanup WebRTC
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()

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
