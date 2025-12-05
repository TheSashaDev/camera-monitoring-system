const Database = require('better-sqlite3');
const path = require('path');

const db = new Database(path.join(__dirname, '..', 'surveillance.db'));

db.exec(`
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    last_seen INTEGER,
    is_online INTEGER DEFAULT 0,
    is_recording INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now'))
  );

  CREATE TABLE IF NOT EXISTS recordings (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    camera_type TEXT NOT NULL,
    filename TEXT NOT NULL,
    file_path TEXT NOT NULL,
    duration INTEGER,
    file_size INTEGER,
    started_at INTEGER,
    ended_at INTEGER,
    created_at INTEGER DEFAULT (strftime('%s', 'now')),
    FOREIGN KEY (device_id) REFERENCES devices(id)
  );

  CREATE INDEX IF NOT EXISTS idx_recordings_device ON recordings(device_id);
  CREATE INDEX IF NOT EXISTS idx_recordings_started ON recordings(started_at);
`);

module.exports = {
  getDevices: () => db.prepare('SELECT * FROM devices ORDER BY last_seen DESC').all(),
  
  getDevice: (id) => db.prepare('SELECT * FROM devices WHERE id = ?').get(id),
  
  upsertDevice: (id, name) => {
    const stmt = db.prepare(`
      INSERT INTO devices (id, name, last_seen, is_online) 
      VALUES (?, ?, strftime('%s', 'now'), 1)
      ON CONFLICT(id) DO UPDATE SET 
        name = excluded.name,
        last_seen = strftime('%s', 'now'),
        is_online = 1
    `);
    return stmt.run(id, name);
  },
  
  setDeviceOnline: (id, online) => {
    return db.prepare('UPDATE devices SET is_online = ?, last_seen = strftime(\'%s\', \'now\') WHERE id = ?').run(online ? 1 : 0, id);
  },
  
  setDeviceRecording: (id, recording) => {
    return db.prepare('UPDATE devices SET is_recording = ? WHERE id = ?').run(recording ? 1 : 0, id);
  },
  
  addRecording: (recording) => {
    const stmt = db.prepare(`
      INSERT INTO recordings (id, device_id, camera_type, filename, file_path, duration, file_size, started_at, ended_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
    return stmt.run(
      recording.id,
      recording.deviceId,
      recording.cameraType,
      recording.filename,
      recording.filePath,
      recording.duration,
      recording.fileSize,
      recording.startedAt,
      recording.endedAt
    );
  },
  
  getRecordings: (deviceId, limit = 100, offset = 0) => {
    if (deviceId) {
      return db.prepare(`
        SELECT r.*, d.name as device_name 
        FROM recordings r 
        JOIN devices d ON r.device_id = d.id 
        WHERE device_id = ? 
        ORDER BY started_at DESC 
        LIMIT ? OFFSET ?
      `).all(deviceId, limit, offset);
    }
    return db.prepare(`
      SELECT r.*, d.name as device_name 
      FROM recordings r 
      JOIN devices d ON r.device_id = d.id 
      ORDER BY started_at DESC 
      LIMIT ? OFFSET ?
    `).all(limit, offset);
  },
  
  getRecording: (id) => db.prepare('SELECT * FROM recordings WHERE id = ?').get(id),
  
  deleteRecording: (id) => db.prepare('DELETE FROM recordings WHERE id = ?').run(id),
  
  db
};
