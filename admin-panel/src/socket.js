import { io } from 'socket.io-client';

const SOCKET_URL = import.meta.env.VITE_API_URL || 'http://localhost:8420';

let socket = null;
let reconnectAttempts = 0;
const MAX_RECONNECT_ATTEMPTS = 10;

export const initSocket = (token, callbacks = {}) => {
  if (socket) {
    socket.disconnect();
  }

  if (!token) {
    console.error('[SOCKET] Token required for connection');
    return null;
  }

  reconnectAttempts = 0;

  socket = io(SOCKET_URL, {
    transports: ['websocket', 'polling'],
    auth: { token },
    reconnection: true,
    reconnectionAttempts: MAX_RECONNECT_ATTEMPTS,
    reconnectionDelay: 1000,
    reconnectionDelayMax: 5000,
    timeout: 20000
  });

  socket.on('connect', () => {
    console.log('[SOCKET] Connected');
    reconnectAttempts = 0;
    socket.emit('admin:register', { token });
    callbacks.onConnect?.();
  });

  socket.on('disconnect', (reason) => {
    console.log('[SOCKET] Disconnected:', reason);
    callbacks.onDisconnect?.(reason);
  });

  socket.on('connect_error', (error) => {
    reconnectAttempts++;
    console.error(`[SOCKET] Connection error (attempt ${reconnectAttempts}/${MAX_RECONNECT_ATTEMPTS}):`, error.message);
    
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      console.error('[SOCKET] Max reconnection attempts reached');
      callbacks.onMaxReconnectFailed?.();
    }
    
    callbacks.onError?.(error);
  });

  socket.on('admin:registered', (data) => {
    console.log('[SOCKET] Admin registered, devices:', data.devices?.length || 0);
    callbacks.onRegistered?.(data);
  });

  socket.on('admin:error', (data) => {
    console.error('[SOCKET] Admin error:', data.error);
    callbacks.onAdminError?.(data);
  });

  socket.on('device:online', (data) => {
    console.log('[SOCKET] Device online:', data.deviceId);
    callbacks.onDeviceOnline?.(data);
  });

  socket.on('device:offline', (data) => {
    console.log('[SOCKET] Device offline:', data.deviceId);
    callbacks.onDeviceOffline?.(data);
  });

  socket.on('device:recording_started', (data) => {
    console.log('[SOCKET] Recording started:', data.deviceId);
    callbacks.onRecordingStarted?.(data);
  });

  socket.on('device:recording_stopped', (data) => {
    console.log('[SOCKET] Recording stopped:', data.deviceId);
    callbacks.onRecordingStopped?.(data);
  });

  socket.on('device:recording_status', (data) => {
    callbacks.onRecordingStatus?.(data);
  });

  socket.on('new_recording', (data) => {
    console.log('[SOCKET] New recording:', data.filename);
    callbacks.onNewRecording?.(data);
  });

  socket.on('command:error', (data) => {
    console.error('[SOCKET] Command error:', data.error);
    callbacks.onCommandError?.(data);
  });

  socket.on('webrtc:error', (data) => {
    console.error('[SOCKET] WebRTC error:', data.error);
    callbacks.onWebRTCError?.(data);
  });

  return socket;
};

export const getSocket = () => socket;

export const isConnected = () => socket?.connected || false;

export const disconnectSocket = () => {
  if (socket) {
    socket.removeAllListeners();
    socket.disconnect();
    socket = null;
  }
};

export const emitWithTimeout = (event, data, timeout = 10000) => {
  return new Promise((resolve, reject) => {
    if (!socket || !socket.connected) {
      reject(new Error('Socket not connected'));
      return;
    }

    const timer = setTimeout(() => {
      reject(new Error('Socket emit timeout'));
    }, timeout);

    socket.emit(event, data, (response) => {
      clearTimeout(timer);
      resolve(response);
    });
  });
};

export default { initSocket, getSocket, disconnectSocket, isConnected, emitWithTimeout };
