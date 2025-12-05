import React, { useState, useEffect } from 'react';
import { recordings as recordingsApi, devices as devicesApi, getRecordingUrl } from '../api';

function Recordings() {
  const [recordings, setRecordings] = useState([]);
  const [devices, setDevices] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState('');
  const [loading, setLoading] = useState(true);
  const [playingVideo, setPlayingVideo] = useState(null);

  useEffect(() => {
    loadDevices();
    loadRecordings();
  }, []);

  useEffect(() => {
    loadRecordings();
  }, [selectedDevice]);

  const loadDevices = async () => {
    try {
      const response = await devicesApi.getAll();
      setDevices(response.data);
    } catch (error) {
      console.error('Failed to load devices:', error);
    }
  };

  const loadRecordings = async () => {
    setLoading(true);
    try {
      const params = selectedDevice ? { deviceId: selectedDevice } : {};
      const response = await recordingsApi.getAll(params);
      setRecordings(response.data);
    } catch (error) {
      console.error('Failed to load recordings:', error);
    } finally {
      setLoading(false);
    }
  };

  const deleteRecording = async (id) => {
    if (!confirm('Are you sure you want to delete this recording?')) return;
    
    try {
      await recordingsApi.delete(id);
      setRecordings(prev => prev.filter(r => r.id !== id));
    } catch (error) {
      console.error('Failed to delete recording:', error);
      alert('Failed to delete recording');
    }
  };

  const formatDuration = (seconds) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const formatFileSize = (bytes) => {
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} KB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const formatDate = (timestamp) => {
    return new Date(timestamp * 1000).toLocaleString();
  };

  return (
    <div>
      <div className="header">
        <h2>Recordings</h2>
        <button className="btn btn-secondary" onClick={loadRecordings}>
          Refresh
        </button>
      </div>

      <div className="filters">
        <select value={selectedDevice} onChange={(e) => setSelectedDevice(e.target.value)}>
          <option value="">All Devices</option>
          {devices.map(device => (
            <option key={device.id} value={device.id}>
              {device.name || device.id.slice(0, 12)}
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <div className="empty-state">
          <p>Loading recordings...</p>
        </div>
      ) : recordings.length > 0 ? (
        <div className="recordings-list">
          {recordings.map(recording => (
            <div key={recording.id} className="recording-item">
              <div className="recording-info">
                <div 
                  className="recording-thumbnail" 
                  onClick={() => setPlayingVideo(recording)}
                  style={{ cursor: 'pointer' }}
                >
                  ▶
                </div>
                <div className="recording-details">
                  <h4>{recording.device_name || recording.device_id?.slice(0, 12)}</h4>
                  <p>
                    {recording.camera_type} camera • 
                    {formatDuration(recording.duration || 0)} • 
                    {formatFileSize(recording.file_size || 0)}
                  </p>
                  <p style={{ marginTop: '4px' }}>
                    {formatDate(recording.started_at)}
                  </p>
                </div>
              </div>
              <div className="recording-actions">
                <button 
                  className="btn btn-primary"
                  onClick={() => setPlayingVideo(recording)}
                >
                  Play
                </button>
                <a 
                  href={getRecordingUrl(recording.file_path)}
                  download
                  className="btn btn-secondary"
                >
                  Download
                </a>
                <button 
                  className="btn btn-danger"
                  onClick={() => deleteRecording(recording.id)}
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div className="empty-state">
          <h3>No recordings found</h3>
          <p>{selectedDevice ? 'No recordings for this device' : 'Recordings will appear here when devices start recording'}</p>
        </div>
      )}

      {playingVideo && (
        <div className="video-modal" onClick={() => setPlayingVideo(null)}>
          <div className="video-modal-content" onClick={(e) => e.stopPropagation()}>
            <button className="video-modal-close" onClick={() => setPlayingVideo(null)}>
              ×
            </button>
            <video 
              controls 
              autoPlay
              src={getRecordingUrl(playingVideo.file_path)}
              style={{ maxWidth: '100%', maxHeight: '80vh', borderRadius: '8px' }}
            />
            <div style={{ padding: '16px', color: '#fff' }}>
              <h4>{playingVideo.device_name || playingVideo.device_id?.slice(0, 12)}</h4>
              <p style={{ color: '#888' }}>
                {playingVideo.camera_type} • {formatDate(playingVideo.started_at)}
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default Recordings;
