require('dotenv').config();

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const os = require('os');

const db = require('./database');
const { authenticateToken, generateToken, comparePassword } = require('./auth');

// Graceful error handling for uncaught exceptions
process.on('uncaughtException', (err) => {
  console.error('[FATAL] Uncaught exception:', err.message);
  console.error(err.stack);
});

process.on('unhandledRejection', (reason, promise) => {
  console.error('[ERROR] Unhandled rejection at:', promise);
  console.error('Reason:', reason);
});

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  },
  maxHttpBufferSize: 50 * 1024 * 1024,
  pingTimeout: 60000,
  pingInterval: 25000
});

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use('/recordings', express.static(path.join(__dirname, '..', 'recordings')));

// Global error handler for express
app.use((err, req, res, next) => {
  console.error('[EXPRESS ERROR]', err.message);
  console.error(err.stack);
  res.status(500).json({ error: 'Internal server error', message: err.message });
});

// Ensure recordings directory exists
const recordingsDir = path.join(__dirname, '..', 'recordings');
if (!fs.existsSync(recordingsDir)) {
  fs.mkdirSync(recordingsDir, { recursive: true });
}

const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    try {
      const dir = path.join(__dirname, '..', 'recordings', req.params.deviceId || 'unknown');
      fs.mkdirSync(dir, { recursive: true });
      cb(null, dir);
    } catch (err) {
      console.error('[MULTER] Directory creation error:', err.message);
      cb(err, null);
    }
  },
  filename: (req, file, cb) => {
    try {
      const ext = path.extname(file.originalname) || '.mp4';
      cb(null, `${Date.now()}-${uuidv4()}${ext}`);
    } catch (err) {
      console.error('[MULTER] Filename generation error:', err.message);
      cb(err, null);
    }
  }
});

const upload = multer({
  storage,
  limits: { fileSize: 500 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    const allowedMimes = ['video/mp4', 'video/webm', 'video/quicktime', 'application/octet-stream'];
    if (allowedMimes.includes(file.mimetype) || file.originalname.endsWith('.mp4')) {
      cb(null, true);
    } else {
      cb(new Error(`Invalid file type: ${file.mimetype}`), false);
    }
  }
});

const connectedDevices = new Map();
const adminSockets = new Map();

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    uptime: process.uptime(),
    timestamp: Date.now(),
    connectedDevices: connectedDevices.size,
    connectedAdmins: adminSockets.size
  });
});

// Auth routes
app.post('/api/auth/login', (req, res) => {
  try {
    const { username, password } = req.body;

    if (!username || !password) {
      return res.status(400).json({ error: 'Username and password are required' });
    }

    if (username === process.env.ADMIN_USERNAME && password === process.env.ADMIN_PASSWORD) {
      const token = generateToken({ username, role: 'admin' });
      return res.json({ token, username });
    }

    res.status(401).json({ error: 'Invalid credentials' });
  } catch (err) {
    console.error('[AUTH] Login error:', err.message);
    res.status(500).json({ error: 'Login failed' });
  }
});

app.get('/api/auth/verify', authenticateToken, (req, res) => {
  try {
    res.json({ valid: true, user: req.user });
  } catch (err) {
    console.error('[AUTH] Verify error:', err.message);
    res.status(500).json({ error: 'Verification failed' });
  }
});

// Device routes
app.get('/api/devices', authenticateToken, (req, res) => {
  try {
    const devices = db.getDevices();
    const devicesWithStatus = devices.map(d => ({
      ...d,
      is_online: connectedDevices.has(d.id) ? 1 : 0
    }));
    res.json(devicesWithStatus);
  } catch (err) {
    console.error('[DEVICES] Get all error:', err.message);
    res.status(500).json({ error: 'Failed to fetch devices' });
  }
});

app.get('/api/devices/:id', authenticateToken, (req, res) => {
  try {
    const device = db.getDevice(req.params.id);
    if (!device) {
      return res.status(404).json({ error: 'Device not found' });
    }
    device.is_online = connectedDevices.has(device.id) ? 1 : 0;
    res.json(device);
  } catch (err) {
    console.error('[DEVICES] Get by ID error:', err.message);
    res.status(500).json({ error: 'Failed to fetch device' });
  }
});

// Recording routes
app.get('/api/recordings', authenticateToken, (req, res) => {
  try {
    const { deviceId, limit = 100, offset = 0 } = req.query;
    const recordings = db.getRecordings(deviceId, parseInt(limit), parseInt(offset));
    res.json(recordings);
  } catch (err) {
    console.error('[RECORDINGS] Get all error:', err.message);
    res.status(500).json({ error: 'Failed to fetch recordings' });
  }
});

app.post('/api/recordings/:deviceId', upload.single('video'), (req, res) => {
  try {
    const { deviceId } = req.params;

    if (!deviceId) {
      return res.status(400).json({ error: 'Device ID is required' });
    }

    const { cameraType, duration, startedAt, endedAt } = req.body;

    if (!req.file) {
      return res.status(400).json({ error: 'No video file uploaded' });
    }

    const recording = {
      id: uuidv4(),
      deviceId,
      cameraType: cameraType || 'unknown',
      filename: req.file.filename,
      filePath: path.relative(path.join(__dirname, '..'), req.file.path),
      duration: parseInt(duration) || 0,
      fileSize: req.file.size,
      startedAt: parseInt(startedAt) || Math.floor(Date.now() / 1000),
      endedAt: parseInt(endedAt) || Math.floor(Date.now() / 1000)
    };

    db.addRecording(recording);

    io.to('admins').emit('new_recording', recording);

    console.log(`[RECORDING] New recording saved: ${recording.filename} (${(req.file.size / 1024 / 1024).toFixed(2)}MB)`);
    res.json({ success: true, recording });
  } catch (error) {
    console.error('[RECORDING] Upload error:', error.message);
    console.error(error.stack);
    res.status(500).json({ error: 'Upload failed', message: error.message });
  }
});

app.delete('/api/recordings/:id', authenticateToken, (req, res) => {
  try {
    const recording = db.getRecording(req.params.id);
    if (!recording) {
      return res.status(404).json({ error: 'Recording not found' });
    }

    const filePath = path.join(__dirname, '..', recording.file_path);
    if (fs.existsSync(filePath)) {
      try {
        fs.unlinkSync(filePath);
      } catch (err) {
        console.error('[RECORDING] File deletion error:', err.message);
      }
    }

    db.deleteRecording(req.params.id);
    res.json({ success: true });
  } catch (err) {
    console.error('[RECORDING] Delete error:', err.message);
    res.status(500).json({ error: 'Failed to delete recording' });
  }
});

// Socket.IO handling with improved error handling
io.on('connection', (socket) => {
  console.log('[SOCKET] New connection:', socket.id);

  socket.on('error', (error) => {
    console.error('[SOCKET] Socket error:', error.message);
  });

  // Device registration
  socket.on('device:register', (data) => {
    try {
      if (!data || !data.deviceId) {
        socket.emit('device:error', { error: 'Device ID is required' });
        return;
      }

      const { deviceId, deviceName, cameras } = data;

      db.upsertDevice(deviceId, deviceName || `Device ${deviceId.slice(0, 8)}`);

      connectedDevices.set(deviceId, {
        socketId: socket.id,
        socket,
        deviceId,
        deviceName,
        isRecording: false,
        cameras: cameras || ['front', 'back'],
        connectedAt: Date.now()
      });

      socket.deviceId = deviceId;
      socket.join(`device:${deviceId}`);

      io.to('admins').emit('device:online', { deviceId, deviceName });

      socket.emit('device:registered', { success: true });
      console.log(`[DEVICE] Registered: ${deviceId} (${deviceName})`);
    } catch (err) {
      console.error('[DEVICE] Registration error:', err.message);
      socket.emit('device:error', { error: 'Registration failed' });
    }
  });

  // Admin registration
  socket.on('admin:register', (data) => {
    try {
      const { token } = data || {};

      if (!token) {
        socket.emit('admin:error', { error: 'Token required' });
        return;
      }

      const jwt = require('jsonwebtoken');
      const decoded = jwt.verify(token, process.env.JWT_SECRET || 'default-secret-change-me');

      if (decoded.role === 'admin') {
        adminSockets.set(socket.id, socket);
        socket.join('admins');
        socket.isAdmin = true;

        const devices = Array.from(connectedDevices.values()).map(d => ({
          deviceId: d.deviceId,
          deviceName: d.deviceName,
          isRecording: d.isRecording,
          cameras: d.cameras
        }));

        socket.emit('admin:registered', { success: true, devices });
        console.log('[ADMIN] Connected:', socket.id);
      } else {
        socket.emit('admin:error', { error: 'Admin role required' });
      }
    } catch (err) {
      console.error('[ADMIN] Registration error:', err.message);
      socket.emit('admin:error', { error: 'Invalid token' });
    }
  });

  // Admin commands to devices
  socket.on('command:start_recording', (data) => {
    try {
      if (!socket.isAdmin) {
        socket.emit('command:error', { error: 'Unauthorized' });
        return;
      }

      const { deviceId, cameras } = data || {};
      if (!deviceId) {
        socket.emit('command:error', { error: 'Device ID required' });
        return;
      }

      const device = connectedDevices.get(deviceId);

      if (device) {
        device.socket.emit('command:start_recording', { cameras: cameras || ['front', 'back'] });
        device.isRecording = true;
        db.setDeviceRecording(deviceId, true);
        io.to('admins').emit('device:recording_started', { deviceId });
        console.log(`[COMMAND] Start recording sent to device: ${deviceId}`);
      } else {
        socket.emit('command:error', { error: 'Device not connected' });
      }
    } catch (err) {
      console.error('[COMMAND] Start recording error:', err.message);
      socket.emit('command:error', { error: 'Command failed' });
    }
  });

  socket.on('command:stop_recording', (data) => {
    try {
      if (!socket.isAdmin) {
        socket.emit('command:error', { error: 'Unauthorized' });
        return;
      }

      const { deviceId } = data || {};
      if (!deviceId) {
        socket.emit('command:error', { error: 'Device ID required' });
        return;
      }

      const device = connectedDevices.get(deviceId);

      if (device) {
        device.socket.emit('command:stop_recording', {});
        device.isRecording = false;
        db.setDeviceRecording(deviceId, false);
        io.to('admins').emit('device:recording_stopped', { deviceId });
        console.log(`[COMMAND] Stop recording sent to device: ${deviceId}`);
      } else {
        socket.emit('command:error', { error: 'Device not connected' });
      }
    } catch (err) {
      console.error('[COMMAND] Stop recording error:', err.message);
      socket.emit('command:error', { error: 'Command failed' });
    }
  });

  // WebRTC Signaling
  socket.on('webrtc:offer', (data) => {
    try {
      const { targetDeviceId, offer, cameraType } = data || {};

      if (!socket.isAdmin) {
        socket.emit('webrtc:error', { error: 'Unauthorized' });
        return;
      }

      if (!targetDeviceId || !offer) {
        socket.emit('webrtc:error', { error: 'Missing required fields' });
        return;
      }

      const device = connectedDevices.get(targetDeviceId);
      if (device) {
        device.socket.emit('webrtc:offer', {
          adminSocketId: socket.id,
          offer,
          cameraType: cameraType || 'back'
        });
      } else {
        socket.emit('webrtc:error', { error: 'Device not connected' });
      }
    } catch (err) {
      console.error('[WEBRTC] Offer error:', err.message);
      socket.emit('webrtc:error', { error: 'WebRTC offer failed' });
    }
  });

  socket.on('webrtc:answer', (data) => {
    try {
      const { adminSocketId, answer, cameraType } = data || {};
      const adminSocket = adminSockets.get(adminSocketId);

      if (adminSocket) {
        adminSocket.emit('webrtc:answer', {
          deviceId: socket.deviceId,
          answer,
          cameraType
        });
      }
    } catch (err) {
      console.error('[WEBRTC] Answer error:', err.message);
    }
  });

  socket.on('webrtc:ice_candidate', (data) => {
    try {
      const { targetId, candidate, cameraType } = data || {};

      if (!targetId || !candidate) return;

      if (socket.isAdmin) {
        const device = connectedDevices.get(targetId);
        if (device) {
          device.socket.emit('webrtc:ice_candidate', {
            adminSocketId: socket.id,
            candidate,
            cameraType
          });
        }
      } else if (socket.deviceId) {
        const adminSocket = adminSockets.get(targetId);
        if (adminSocket) {
          adminSocket.emit('webrtc:ice_candidate', {
            deviceId: socket.deviceId,
            candidate,
            cameraType
          });
        }
      }
    } catch (err) {
      console.error('[WEBRTC] ICE candidate error:', err.message);
    }
  });

  socket.on('webrtc:stop', (data) => {
    try {
      const { targetDeviceId, cameraType } = data || {};

      if (socket.isAdmin) {
        const device = connectedDevices.get(targetDeviceId);
        if (device) {
          device.socket.emit('webrtc:stop', {
            adminSocketId: socket.id,
            cameraType
          });
        }
      }
    } catch (err) {
      console.error('[WEBRTC] Stop error:', err.message);
    }
  });

  // Device status updates
  socket.on('device:recording_status', (data) => {
    try {
      const { isRecording } = data || {};
      const device = connectedDevices.get(socket.deviceId);

      if (device) {
        device.isRecording = isRecording;
        db.setDeviceRecording(socket.deviceId, isRecording);
        io.to('admins').emit('device:recording_status', {
          deviceId: socket.deviceId,
          isRecording
        });
      }
    } catch (err) {
      console.error('[DEVICE] Recording status update error:', err.message);
    }
  });

  socket.on('disconnect', (reason) => {
    try {
      if (socket.deviceId) {
        connectedDevices.delete(socket.deviceId);
        db.setDeviceOnline(socket.deviceId, false);
        io.to('admins').emit('device:offline', { deviceId: socket.deviceId });
        console.log(`[DEVICE] Disconnected: ${socket.deviceId} (reason: ${reason})`);
      }

      if (socket.isAdmin) {
        adminSockets.delete(socket.id);
        console.log(`[ADMIN] Disconnected: ${socket.id} (reason: ${reason})`);
      }
    } catch (err) {
      console.error('[SOCKET] Disconnect error:', err.message);
    }
  });
});

// Get local IP addresses
function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const ips = [];
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        ips.push({ name, address: iface.address });
      }
    }
  }
  return ips;
}

const PORT = process.env.PORT || 8420;
server.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('='.repeat(60));
  console.log('  Camera Surveillance Server Started');
  console.log('='.repeat(60));
  console.log(`  Port: ${PORT}`);
  console.log('');
  console.log('  Network Addresses:');
  console.log(`  - Local:    http://localhost:${PORT}`);
  console.log(`  - Local:    http://127.0.0.1:${PORT}`);

  const localIPs = getLocalIPs();
  localIPs.forEach(ip => {
    console.log(`  - ${ip.name}:  http://${ip.address}:${PORT}`);
  });

  console.log('');
  console.log('  Endpoints:');
  console.log(`  - API:      http://<ip>:${PORT}/api`);
  console.log(`  - Health:   http://<ip>:${PORT}/health`);
  console.log(`  - Recordings: http://<ip>:${PORT}/recordings`);
  console.log('');
  console.log('  Admin Panel should connect to this server');
  console.log('='.repeat(60));
  console.log('');
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('[SERVER] SIGTERM received, shutting down gracefully...');
  server.close(() => {
    console.log('[SERVER] HTTP server closed');
    process.exit(0);
  });
});

process.on('SIGINT', () => {
  console.log('[SERVER] SIGINT received, shutting down gracefully...');
  server.close(() => {
    console.log('[SERVER] HTTP server closed');
    process.exit(0);
  });
});
