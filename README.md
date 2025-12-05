# Camera Surveillance System

A complete surveillance system with Android app for camera recording/streaming and a web-based admin panel.

## Features

- **Android App**
  - Keeps screen on (no lock screen while app is open)
  - Dual camera recording (front + back simultaneously)
  - Live streaming via WebRTC
  - Server-controlled recording (start/stop from admin panel)
  - Auto-reconnection on connection loss
  - Background service with foreground notification
  - Auto-upload recordings to server

- **Admin Panel**
  - Real-time device status monitoring
  - Live camera preview (WebRTC)
  - Start/stop recording remotely
  - View and playback recordings
  - Download recordings
  - Device management

- **Server**
  - WebSocket real-time communication
  - WebRTC signaling for live streams
  - REST API for recordings management
  - SQLite database for persistence
  - JWT authentication

## Project Structure

```
camera-surveillance-system/
├── server/              # Node.js backend
├── admin-panel/         # React admin dashboard
└── android-app/         # Android application
```

## Quick Start

### 1. Start the Server

```bash
cd server
npm install
npm start
```

Server runs on `http://localhost:3000`

Default credentials:
- Username: `admin`
- Password: `admin123`

### 2. Start the Admin Panel

```bash
cd admin-panel
npm install
npm run dev
```

Admin panel runs on `http://localhost:5173`

### 3. Build Android App

1. Open `android-app` folder in Android Studio
2. Sync Gradle files
3. Build and install on device

### 4. Connect Device

1. Open the app on your Android device
2. Enter server URL (e.g., `http://YOUR_SERVER_IP:3000`)
3. Click "Connect"
4. The device will appear in the admin panel

## Configuration

### Server (.env)

```env
PORT=3000
JWT_SECRET=your-secret-key-change-in-production
ADMIN_USERNAME=admin
ADMIN_PASSWORD=admin123
```

### Production Deployment

1. **Server**: Deploy to a cloud provider (AWS, DigitalOcean, etc.)
   - Use HTTPS (nginx reverse proxy recommended)
   - Set strong JWT_SECRET
   - Configure firewall rules

2. **Admin Panel**: Build and serve static files
   ```bash
   cd admin-panel
   npm run build
   # Serve dist/ folder with nginx or similar
   ```

3. **Android**: Update server URL in the app and build release APK

## API Endpoints

### Authentication
- `POST /api/auth/login` - Login with username/password
- `GET /api/auth/verify` - Verify JWT token

### Devices
- `GET /api/devices` - List all devices
- `GET /api/devices/:id` - Get device details

### Recordings
- `GET /api/recordings` - List recordings (filter by deviceId)
- `POST /api/recordings/:deviceId` - Upload recording
- `DELETE /api/recordings/:id` - Delete recording

## Socket Events

### Device Events
- `device:register` - Register device with server
- `device:online` - Device came online
- `device:offline` - Device went offline
- `device:recording_status` - Recording status changed

### Admin Commands
- `command:start_recording` - Start recording on device
- `command:stop_recording` - Stop recording on device

### WebRTC Signaling
- `webrtc:offer` - Send WebRTC offer
- `webrtc:answer` - Send WebRTC answer
- `webrtc:ice_candidate` - Exchange ICE candidates

## Android Permissions

The app requires:
- `CAMERA` - Camera access
- `RECORD_AUDIO` - Audio recording
- `INTERNET` - Network access
- `WAKE_LOCK` - Keep device awake
- `FOREGROUND_SERVICE` - Background service
- `POST_NOTIFICATIONS` - Show service notification (Android 13+)

## Troubleshooting

### Device not connecting
1. Ensure server is accessible from device network
2. Check firewall allows port 3000
3. Use IP address, not localhost
4. Check Android logs for connection errors

### Recording not working
1. Grant camera and microphone permissions
2. Check available storage space
3. Ensure device is connected (green status in admin)

### Live stream not working
1. WebRTC requires STUN servers (configured by default)
2. May need TURN server for restrictive networks
3. Check browser console for WebRTC errors

## License

MIT
