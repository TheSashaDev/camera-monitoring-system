# Socket.IO
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**
-keep class okio.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# WebRTC
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Keep app models
-keep class com.surveillance.app.** { *; }
