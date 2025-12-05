const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');

const JWT_SECRET = process.env.JWT_SECRET || 'default-secret-change-me';
const TOKEN_EXPIRY = process.env.TOKEN_EXPIRY || '7d';

const authenticateToken = (req, res, next) => {
  try {
    const authHeader = req.headers['authorization'];
    const token = authHeader && authHeader.split(' ')[1];

    if (!token) {
      return res.status(401).json({ error: 'Access token required' });
    }

    jwt.verify(token, JWT_SECRET, (err, user) => {
      if (err) {
        if (err.name === 'TokenExpiredError') {
          return res.status(403).json({ error: 'Token expired' });
        }
        if (err.name === 'JsonWebTokenError') {
          return res.status(403).json({ error: 'Invalid token' });
        }
        console.error('[AUTH] Token verification error:', err.message);
        return res.status(403).json({ error: 'Token verification failed' });
      }
      req.user = user;
      next();
    });
  } catch (err) {
    console.error('[AUTH] Authentication error:', err.message);
    return res.status(500).json({ error: 'Authentication error' });
  }
};

const authenticateSocket = (socket, next) => {
  try {
    const token = socket.handshake.auth?.token;

    if (!token) {
      return next(new Error('Authentication required'));
    }

    jwt.verify(token, JWT_SECRET, (err, user) => {
      if (err) {
        console.error('[AUTH] Socket token error:', err.message);
        return next(new Error('Invalid token'));
      }
      socket.user = user;
      next();
    });
  } catch (err) {
    console.error('[AUTH] Socket authentication error:', err.message);
    return next(new Error('Authentication error'));
  }
};

const generateToken = (payload) => {
  try {
    if (!payload) {
      throw new Error('Payload is required');
    }
    return jwt.sign(payload, JWT_SECRET, { expiresIn: TOKEN_EXPIRY });
  } catch (err) {
    console.error('[AUTH] Token generation error:', err.message);
    throw err;
  }
};

const verifyToken = (token) => {
  try {
    if (!token) {
      return { valid: false, error: 'Token required' };
    }
    const decoded = jwt.verify(token, JWT_SECRET);
    return { valid: true, decoded };
  } catch (err) {
    return { valid: false, error: err.message };
  }
};

const hashPassword = (password) => {
  try {
    if (!password) {
      throw new Error('Password is required');
    }
    return bcrypt.hashSync(password, 10);
  } catch (err) {
    console.error('[AUTH] Password hashing error:', err.message);
    throw err;
  }
};

const comparePassword = (password, hash) => {
  try {
    if (!password || !hash) {
      return false;
    }
    return bcrypt.compareSync(password, hash);
  } catch (err) {
    console.error('[AUTH] Password comparison error:', err.message);
    return false;
  }
};

module.exports = {
  authenticateToken,
  authenticateSocket,
  generateToken,
  verifyToken,
  hashPassword,
  comparePassword,
  JWT_SECRET
};
