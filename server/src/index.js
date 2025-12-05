require('dotenv').config();

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');
const path = require('path');
const multer = require('multer');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');

const db = require('./database');
const { authenticateToken, generateToken, comparePassword } = require('./auth');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  },
  maxHttpBufferSize: 50 * 1024 * 1024
});

app.use(cors());
app.use(express.json());
app.use('/recordings', express.static(path.join(__dirname, '..', 'recordings')));

const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    const dir = path.join(__dirname, '..', 'recordings', req.params.deviceId || 'unknown');
    fs.mkdirSync(dir, { recursive: true });
    cb(null, dir);
  },
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname) || '.mp4';
    cb(null, `${Date.now()}-${uuidv4()}${ext}`);
  }
});
const upload = multer({ storage, limits: { fileSize: 500 * 1024 * 1024 } });

const connectedDevices = new Map();
const adminSockets = new Map();

// Auth routes
app.post('/api/auth/login', (req, res) => {
  const { username, password } = req.body;
  
  if (username === process.env.ADMIN_USERNAME && password === process.env.ADMIN_PASSWORD) {
    const token = generateToken({ username, role: 'admin' });
    return res.json({ token, username });
  }
  
  res.status(401).json({ error: 'Invalid credentials' });
});

app.get('/api/auth/verify', authenticateToken, (req, res) => {
  res.json({ valid: true, user: req.user });
});

// Device routes
app.get('/api/devices', authenticateToken, (req, res) => {
  const devices = db.getDevices();
  const devicesWithStatus = devices.map(d => ({
    ...d,
    is_online: connectedDevices.has(d.id) ? 1 : 0
  }));
  res.json(devicesWithStatus);
});

app.get('/api/devices/:id', authenticateToken, (req, res) => {
  const device = db.getDevice(req.params.id);
  if (!device) {
    return res.status(404).json({ error: 'Device not found' });
  }
  device.is_online = connectedDevices.has(device.id) ? 1 : 0;
  res.json(device);
});

// Recording routes
app.get('/api/recordings', authenticateToken, (req, res) => {
  const { deviceId, limit = 100, offset = 0 } = req.query;
  const recordings = db.getRecordings(deviceId, parseInt(limit), parseInt(offset));
  res.json(recordings);
});

app.post('/api/recordings/:deviceId', upload.single('video'), (req, res) => {
  try {
    const { deviceId } = req.params;
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
    
    res.json({ success: true, recording });
  } catch (error) {
    console.error('Upload error:', error);
    res.status(500).json({ error: 'Upload failed' });
  }
});

app.delete('/api/recordings/:id', authenticateToken, (req, res) => {
  const recording = db.getRecording(req.params.id);
  if (!recording) {
    return res.status(404).json({ error: 'Recording not found' });
  }
  
  const filePath = path.join(__dirname, '..', recording.file_path);
  if (fs.existsSync(filePath)) {
    fs.unlinkSync(filePath);
  }
  
  db.deleteRecording(req.params.id);
  res.json({ success: true });
});

// Socket.IO handling
io.on('connection', (socket) => {
  console.log('Socket connected:', socket.id);
  
  // Device registration
  socket.on('device:register', (data) => {
    const { deviceId, deviceName } = data;
    
    db.upsertDevice(deviceId, deviceName || `Device ${deviceId.slice(0, 8)}`);
    
    connectedDevices.set(deviceId, {
      socketId: socket.id,
      socket,
      deviceId,
      deviceName,
      isRecording: false,
      cameras: data.cameras || ['front', 'back']
    });
    
    socket.deviceId = deviceId;
    socket.join(`device:${deviceId}`);
    
    io.to('admins').emit('device:online', { deviceId, deviceName });
    
    socket.emit('device:registered', { success: true });
    console.log(`Device registered: ${deviceId}`);
  });
  
  // Admin registration
  socket.on('admin:register', (data) => {
    const { token } = data;
    
    try {
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
        console.log('Admin connected:', socket.id);
      }
    } catch (err) {
      socket.emit('admin:error', { error: 'Invalid token' });
    }
  });
  
  // Admin commands to devices
  socket.on('command:start_recording', (data) => {
    if (!socket.isAdmin) return;
    
    const { deviceId, cameras } = data;
    const device = connectedDevices.get(deviceId);
    
    if (device) {
      device.socket.emit('command:start_recording', { cameras: cameras || ['front', 'back'] });
      device.isRecording = true;
      db.setDeviceRecording(deviceId, true);
      io.to('admins').emit('device:recording_started', { deviceId });
    }
  });
  
  socket.on('command:stop_recording', (data) => {
    if (!socket.isAdmin) return;
    
    const { deviceId } = data;
    const device = connectedDevices.get(deviceId);
    
    if (device) {
      device.socket.emit('command:stop_recording', {});
      device.isRecording = false;
      db.setDeviceRecording(deviceId, false);
      io.to('admins').emit('device:recording_stopped', { deviceId });
    }
  });
  
  // WebRTC Signaling
  socket.on('webrtc:offer', (data) => {
    const { targetDeviceId, offer, cameraType } = data;
    
    if (socket.isAdmin) {
      const device = connectedDevices.get(targetDeviceId);
      if (device) {
        device.socket.emit('webrtc:offer', {
          adminSocketId: socket.id,
          offer,
          cameraType
        });
      }
    }
  });
  
  socket.on('webrtc:answer', (data) => {
    const { adminSocketId, answer, cameraType } = data;
    const adminSocket = adminSockets.get(adminSocketId);
    
    if (adminSocket) {
      adminSocket.emit('webrtc:answer', {
        deviceId: socket.deviceId,
        answer,
        cameraType
      });
    }
  });
  
  socket.on('webrtc:ice_candidate', (data) => {
    const { targetId, candidate, cameraType } = data;
    
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
  });
  
  socket.on('webrtc:stop', (data) => {
    const { targetDeviceId, cameraType } = data;
    
    if (socket.isAdmin) {
      const device = connectedDevices.get(targetDeviceId);
      if (device) {
        device.socket.emit('webrtc:stop', {
          adminSocketId: socket.id,
          cameraType
        });
      }
    }
  });
  
  // Device status updates
  socket.on('device:recording_status', (data) => {
    const { isRecording } = data;
    const device = connectedDevices.get(socket.deviceId);
    
    if (device) {
      device.isRecording = isRecording;
      db.setDeviceRecording(socket.deviceId, isRecording);
      io.to('admins').emit('device:recording_status', {
        deviceId: socket.deviceId,
        isRecording
      });
    }
  });
  
  socket.on('disconnect', () => {
    if (socket.deviceId) {
      connectedDevices.delete(socket.deviceId);
      db.setDeviceOnline(socket.deviceId, false);
      io.to('admins').emit('device:offline', { deviceId: socket.deviceId });
      console.log(`Device disconnected: ${socket.deviceId}`);
    }
    
    if (socket.isAdmin) {
      adminSockets.delete(socket.id);
      console.log('Admin disconnected:', socket.id);
    }
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, '0.0.0.0', () => {
  console.log(`Server running on port ${PORT}`);
  console.log(`Admin panel: http://localhost:${PORT}`);
});
