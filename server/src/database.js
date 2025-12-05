const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

const dbDir = path.join(__dirname, '..');
const dbPath = path.join(dbDir, 'surveillance.db');

// Ensure the directory exists
if (!fs.existsSync(dbDir)) {
  fs.mkdirSync(dbDir, { recursive: true });
}

let db;

try {
  db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.pragma('synchronous = NORMAL');
  console.log('[DATABASE] Connected to:', dbPath);
} catch (err) {
  console.error('[DATABASE] Failed to connect:', err.message);
  process.exit(1);
}

try {
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
  console.log('[DATABASE] Schema initialized');
} catch (err) {
  console.error('[DATABASE] Schema initialization failed:', err.message);
  process.exit(1);
}

function safeRun(fn, defaultValue = null) {
  return function (...args) {
    try {
      return fn.apply(this, args);
    } catch (err) {
      console.error('[DATABASE] Query error:', err.message);
      return defaultValue;
    }
  };
}

module.exports = {
  getDevices: safeRun(() => {
    return db.prepare('SELECT * FROM devices ORDER BY last_seen DESC').all();
  }, []),

  getDevice: safeRun((id) => {
    if (!id) return null;
    return db.prepare('SELECT * FROM devices WHERE id = ?').get(id);
  }, null),

  upsertDevice: safeRun((id, name) => {
    if (!id) throw new Error('Device ID is required');
    const stmt = db.prepare(`
      INSERT INTO devices (id, name, last_seen, is_online) 
      VALUES (?, ?, strftime('%s', 'now'), 1)
      ON CONFLICT(id) DO UPDATE SET 
        name = excluded.name,
        last_seen = strftime('%s', 'now'),
        is_online = 1
    `);
    return stmt.run(id, name || `Device ${id.slice(0, 8)}`);
  }),

  setDeviceOnline: safeRun((id, online) => {
    if (!id) return null;
    return db.prepare('UPDATE devices SET is_online = ?, last_seen = strftime(\'%s\', \'now\') WHERE id = ?').run(online ? 1 : 0, id);
  }),

  setDeviceRecording: safeRun((id, recording) => {
    if (!id) return null;
    return db.prepare('UPDATE devices SET is_recording = ? WHERE id = ?').run(recording ? 1 : 0, id);
  }),

  addRecording: safeRun((recording) => {
    if (!recording || !recording.id || !recording.deviceId) {
      throw new Error('Invalid recording data');
    }
    const stmt = db.prepare(`
      INSERT INTO recordings (id, device_id, camera_type, filename, file_path, duration, file_size, started_at, ended_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `);
    return stmt.run(
      recording.id,
      recording.deviceId,
      recording.cameraType || 'unknown',
      recording.filename,
      recording.filePath,
      recording.duration || 0,
      recording.fileSize || 0,
      recording.startedAt,
      recording.endedAt
    );
  }),

  getRecordings: safeRun((deviceId, limit = 100, offset = 0) => {
    const safeLimit = Math.min(Math.max(1, parseInt(limit) || 100), 1000);
    const safeOffset = Math.max(0, parseInt(offset) || 0);

    if (deviceId) {
      return db.prepare(`
        SELECT r.*, d.name as device_name 
        FROM recordings r 
        LEFT JOIN devices d ON r.device_id = d.id 
        WHERE device_id = ? 
        ORDER BY started_at DESC 
        LIMIT ? OFFSET ?
      `).all(deviceId, safeLimit, safeOffset);
    }
    return db.prepare(`
      SELECT r.*, d.name as device_name 
      FROM recordings r 
      LEFT JOIN devices d ON r.device_id = d.id 
      ORDER BY started_at DESC 
      LIMIT ? OFFSET ?
    `).all(safeLimit, safeOffset);
  }, []),

  getRecording: safeRun((id) => {
    if (!id) return null;
    return db.prepare('SELECT * FROM recordings WHERE id = ?').get(id);
  }, null),

  deleteRecording: safeRun((id) => {
    if (!id) return null;
    return db.prepare('DELETE FROM recordings WHERE id = ?').run(id);
  }),

  getStats: safeRun(() => {
    const deviceCount = db.prepare('SELECT COUNT(*) as count FROM devices').get();
    const recordingCount = db.prepare('SELECT COUNT(*) as count FROM recordings').get();
    const totalSize = db.prepare('SELECT SUM(file_size) as total FROM recordings').get();
    return {
      devices: deviceCount?.count || 0,
      recordings: recordingCount?.count || 0,
      totalSize: totalSize?.total || 0
    };
  }, { devices: 0, recordings: 0, totalSize: 0 }),

  close: () => {
    try {
      db.close();
      console.log('[DATABASE] Connection closed');
    } catch (err) {
      console.error('[DATABASE] Close error:', err.message);
    }
  },

  db
};
