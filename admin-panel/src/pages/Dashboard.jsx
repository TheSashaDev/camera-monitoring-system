import React, { useState, useEffect } from 'react';
import { useAuth } from '../App';
import { devices as devicesApi, recordings as recordingsApi } from '../api';

function Dashboard() {
  const { devices } = useAuth();
  const [recordings, setRecordings] = useState([]);
  const [stats, setStats] = useState({ totalDevices: 0, onlineDevices: 0, recordingDevices: 0, totalRecordings: 0 });

  useEffect(() => {
    loadData();
  }, [devices]);

  const loadData = async () => {
    try {
      const [devicesRes, recordingsRes] = await Promise.all([
        devicesApi.getAll(),
        recordingsApi.getAll({ limit: 5 })
      ]);
      
      const dbDevices = devicesRes.data;
      const onlineCount = devices.filter(d => d.isOnline !== false).length;
      const recordingCount = devices.filter(d => d.isRecording).length;
      
      setStats({
        totalDevices: Math.max(dbDevices.length, devices.length),
        onlineDevices: onlineCount,
        recordingDevices: recordingCount,
        totalRecordings: recordingsRes.data.length
      });
      
      setRecordings(recordingsRes.data);
    } catch (error) {
      console.error('Failed to load data:', error);
    }
  };

  return (
    <div>
      <div className="header">
        <h2>Dashboard</h2>
      </div>
      
      <div className="stats-grid">
        <div className="stat-card">
          <h3>Total Devices</h3>
          <div className="value">{stats.totalDevices}</div>
        </div>
        <div className="stat-card">
          <h3>Online</h3>
          <div className="value online">{stats.onlineDevices}</div>
        </div>
        <div className="stat-card">
          <h3>Recording</h3>
          <div className="value recording">{stats.recordingDevices}</div>
        </div>
        <div className="stat-card">
          <h3>Total Recordings</h3>
          <div className="value">{stats.totalRecordings}</div>
        </div>
      </div>

      <h3 style={{ marginBottom: '20px' }}>Online Devices</h3>
      {devices.filter(d => d.isOnline !== false).length > 0 ? (
        <div className="devices-grid" style={{ marginBottom: '40px' }}>
          {devices.filter(d => d.isOnline !== false).map(device => (
            <div key={device.deviceId} className="device-card">
              <div className="device-header">
                <div className="device-name">
                  <span className={`status-dot ${device.isRecording ? 'recording' : 'online'}`}></span>
                  {device.deviceName || device.deviceId?.slice(0, 12)}
                </div>
                <span style={{ color: '#888', fontSize: '12px' }}>
                  {device.isRecording ? 'Recording' : 'Online'}
                </span>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-state" style={{ marginBottom: '40px' }}>
          <h3>No devices online</h3>
          <p>Connect an Android device to get started</p>
        </div>
      )}

      <h3 style={{ marginBottom: '20px' }}>Recent Recordings</h3>
      {recordings.length > 0 ? (
        <div className="recordings-list">
          {recordings.slice(0, 5).map(rec => (
            <div key={rec.id} className="recording-item">
              <div className="recording-info">
                <div className="recording-thumbnail">▶</div>
                <div className="recording-details">
                  <h4>{rec.device_name || rec.device_id?.slice(0, 12)}</h4>
                  <p>
                    {rec.camera_type} camera • {Math.round(rec.duration / 60)} min • 
                    {new Date(rec.started_at * 1000).toLocaleString()}
                  </p>
                </div>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-state">
          <h3>No recordings yet</h3>
          <p>Recordings will appear here when devices start recording</p>
        </div>
      )}
    </div>
  );
}

export default Dashboard;
