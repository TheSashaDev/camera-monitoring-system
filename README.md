# Camera Monitoring System

A controlled camera monitoring experiment with an Android client, Node.js server, and web-based admin panel.

> This project is intended for authorized devices and lab/demo environments only.

## Features

### Android app

- Camera recording/streaming experiment
- Foreground service support
- Auto-reconnect logic
- Upload recordings to the server

### Admin panel

- Device status dashboard
- Recording controls
- Playback/download interface
- WebRTC preview/signaling flow

### Server

- WebSocket real-time communication
- WebRTC signaling
- REST API for recordings
- SQLite persistence
- JWT-based authentication
- Dockerfile for deployment

## Project structure

```text
.
├── android-app/   # Android application
├── admin-panel/   # React admin dashboard
└── server/        # Backend API and signaling server
```

## Quick start

```bash
cd server
npm install
npm start
```

```bash
cd admin-panel
npm install
npm run dev
```

Open `android-app` in Android Studio to build the client.

## Security and ethics

Use only with devices/accounts you own or are explicitly authorized to test. Change all demo credentials before deployment and do not expose the server publicly without proper authentication and HTTPS.
