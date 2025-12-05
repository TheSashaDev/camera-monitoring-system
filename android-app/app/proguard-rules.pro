# Socket.IO
-keep class io.socket.** { *; }
-keep class org.webrtc.** { *; }

# Keep WebRTC classes
-dontwarn org.webrtc.**
-keep class org.webrtc.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }
