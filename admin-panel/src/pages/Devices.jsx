import React, { useState, useEffect, useRef } from 'react';
import { useAuth } from '../App';
import { devices as devicesApi } from '../api';
import { getSocket } from '../socket';

function Devices() {
  const { devices: liveDevices } = useAuth();
  const [devices, setDevices] = useState([]);
  const [activeStreams, setActiveStreams] = useState({});
  const [fullscreenStream, setFullscreenStream] = useState(null);
  const peerConnections = useRef({});
  const videoRefs = useRef({});

  useEffect(() => {
    loadDevices();
  }, [liveDevices]);

  useEffect(() => {
    const socket = getSocket();
    if (!socket) return;

    socket.on('webrtc:answer', handleWebRTCAnswer);
    socket.on('webrtc:ice_candidate', handleICECandidate);

    return () => {
      socket.off('webrtc:answer', handleWebRTCAnswer);
      socket.off('webrtc:ice_candidate', handleICECandidate);
      Object.values(peerConnections.current).forEach(pc => pc.close());
    };
  }, []);

  const loadDevices = async () => {
    try {
      const response = await devicesApi.getAll();
      const mergedDevices = response.data.map(dbDevice => {
        const liveDevice = liveDevices.find(d => d.deviceId === dbDevice.id);
        return {
          ...dbDevice,
          deviceId: dbDevice.id,
          isOnline: liveDevice ? liveDevice.isOnline !== false : dbDevice.is_online === 1,
          isRecording: liveDevice?.isRecording || dbDevice.is_recording === 1
        };
      });
      setDevices(mergedDevices);
    } catch (error) {
      console.error('Failed to load devices:', error);
    }
  };

  const handleWebRTCAnswer = async ({ deviceId, answer, cameraType }) => {
    const key = `${deviceId}-${cameraType}`;
    const pc = peerConnections.current[key];
    if (pc) {
      try {
        await pc.setRemoteDescription(new RTCSessionDescription(answer));
      } catch (err) {
        console.error('Error setting remote description:', err);
      }
    }
  };

  const handleICECandidate = ({ deviceId, candidate, cameraType }) => {
    const key = `${deviceId}-${cameraType}`;
    const pc = peerConnections.current[key];
    if (pc && candidate) {
      pc.addIceCandidate(new RTCIceCandidate(candidate)).catch(console.error);
    }
  };

  const startStream = async (deviceId, cameraType) => {
    const socket = getSocket();
    if (!socket) return;

    const key = `${deviceId}-${cameraType}`;
    
    if (peerConnections.current[key]) {
      peerConnections.current[key].close();
    }

    const pc = new RTCPeerConnection({
      iceServers: [
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
      ]
    });

    peerConnections.current[key] = pc;

    pc.ontrack = (event) => {
      const videoEl = videoRefs.current[key];
      if (videoEl && event.streams[0]) {
        videoEl.srcObject = event.streams[0];
      }
      setActiveStreams(prev => ({ ...prev, [key]: true }));
    };

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        socket.emit('webrtc:ice_candidate', {
          targetId: deviceId,
          candidate: event.candidate,
          cameraType
        });
      }
    };

    pc.oniceconnectionstatechange = () => {
      if (pc.iceConnectionState === 'disconnected' || pc.iceConnectionState === 'failed') {
        stopStream(deviceId, cameraType);
      }
    };

    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });

    try {
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      
      socket.emit('webrtc:offer', {
        targetDeviceId: deviceId,
        offer: pc.localDescription,
        cameraType
      });
    } catch (err) {
      console.error('Error creating offer:', err);
    }
  };

  const stopStream = (deviceId, cameraType) => {
    const socket = getSocket();
    const key = `${deviceId}-${cameraType}`;
    
    if (peerConnections.current[key]) {
      peerConnections.current[key].close();
      delete peerConnections.current[key];
    }

    const videoEl = videoRefs.current[key];
    if (videoEl) {
      videoEl.srcObject = null;
    }

    setActiveStreams(prev => {
      const newStreams = { ...prev };
      delete newStreams[key];
      return newStreams;
    });

    if (socket) {
      socket.emit('webrtc:stop', { targetDeviceId: deviceId, cameraType });
    }
  };

  const startRecording = (deviceId) => {
    const socket = getSocket();
    if (socket) {
      socket.emit('command:start_recording', { deviceId, cameras: ['front', 'back'] });
    }
  };

  const stopRecording = (deviceId) => {
    const socket = getSocket();
    if (socket) {
      socket.emit('command:stop_recording', { deviceId });
    }
  };

  const openFullscreen = (deviceId, cameraType) => {
    setFullscreenStream({ deviceId, cameraType });
  };

  const closeFullscreen = () => {
    setFullscreenStream(null);
  };

  return (
    <div>
      <div className="header">
        <h2>Devices</h2>
      </div>

      {devices.length > 0 ? (
        <div className="devices-grid">
          {devices.map(device => (
            <div key={device.deviceId} className="device-card">
              <div className="device-header">
                <div className="device-name">
                  <span className={`status-dot ${!device.isOnline ? 'offline' : device.isRecording ? 'recording' : 'online'}`}></span>
                  {device.name || device.deviceId?.slice(0, 12)}
                </div>
                <span style={{ color: '#888', fontSize: '12px' }}>
                  {!device.isOnline ? 'Offline' : device.isRecording ? 'Recording' : 'Online'}
                </span>
              </div>
              
              <div className="device-cameras">
                {['front', 'back'].map(cameraType => {
                  const key = `${device.deviceId}-${cameraType}`;
                  const isStreaming = activeStreams[key];
                  
                  return (
                    <div key={cameraType} className="camera-preview" onClick={() => isStreaming && openFullscreen(device.deviceId, cameraType)}>
                      <video
                        ref={el => videoRefs.current[key] = el}
                        autoPlay
                        playsInline
                        muted
                        style={{ display: isStreaming ? 'block' : 'none' }}
                      />
                      {!isStreaming && (
                        <span>{cameraType} camera</span>
                      )}
                      <span className="camera-label">{cameraType}</span>
                      {device.isOnline && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            isStreaming ? stopStream(device.deviceId, cameraType) : startStream(device.deviceId, cameraType);
                          }}
                          className="btn btn-primary"
                          style={{
                            position: 'absolute',
                            bottom: '8px',
                            right: '8px',
                            padding: '6px 12px',
                            fontSize: '11px'
                          }}
                        >
                          {isStreaming ? 'Stop' : 'View'}
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>

              <div className="device-actions">
                {device.isOnline ? (
                  device.isRecording ? (
                    <button className="btn btn-danger" onClick={() => stopRecording(device.deviceId)}>
                      Stop Recording
                    </button>
                  ) : (
                    <button className="btn btn-success" onClick={() => startRecording(device.deviceId)}>
                      Start Recording
                    </button>
                  )
                ) : (
                  <button className="btn btn-secondary" disabled>
                    Device Offline
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-state">
          <h3>No devices registered</h3>
          <p>Install the Android app on a device to get started</p>
        </div>
      )}

      {fullscreenStream && (
        <div className="fullscreen-view">
          <div className="fullscreen-header">
            <h3>{fullscreenStream.cameraType} Camera</h3>
            <button className="btn btn-secondary" onClick={closeFullscreen}>
              Close
            </button>
          </div>
          <div className="fullscreen-content">
            <video
              autoPlay
              playsInline
              ref={el => {
                if (el) {
                  const key = `${fullscreenStream.deviceId}-${fullscreenStream.cameraType}`;
                  const sourceVideo = videoRefs.current[key];
                  if (sourceVideo?.srcObject) {
                    el.srcObject = sourceVideo.srcObject;
                  }
                }
              }}
            />
          </div>
        </div>
      )}
    </div>
  );
}

export default Devices;
