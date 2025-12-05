import axios from 'axios';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8420';

const api = axios.create({
  baseURL: `${API_URL}/api`,
  timeout: 30000
});

api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    console.error('[API] Request error:', error.message);
    return Promise.reject(error);
  }
);

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.code === 'ECONNABORTED') {
      console.error('[API] Request timeout');
      return Promise.reject(new Error('Request timeout. Please try again.'));
    }

    if (!error.response) {
      console.error('[API] Network error:', error.message);
      return Promise.reject(new Error('Network error. Please check your connection.'));
    }

    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('token');
      window.location.href = '/login';
      return Promise.reject(new Error('Session expired. Please login again.'));
    }

    if (error.response?.status >= 500) {
      console.error('[API] Server error:', error.response?.data?.error || error.message);
      return Promise.reject(new Error('Server error. Please try again later.'));
    }

    return Promise.reject(error);
  }
);

export const auth = {
  login: async (username, password) => {
    try {
      return await api.post('/auth/login', { username, password });
    } catch (error) {
      throw new Error(error.response?.data?.error || 'Login failed');
    }
  },
  verify: async () => {
    try {
      return await api.get('/auth/verify');
    } catch (error) {
      throw new Error('Token verification failed');
    }
  }
};

export const devices = {
  getAll: async () => {
    try {
      return await api.get('/devices');
    } catch (error) {
      throw new Error(error.response?.data?.error || 'Failed to fetch devices');
    }
  },
  get: async (id) => {
    try {
      return await api.get(`/devices/${id}`);
    } catch (error) {
      throw new Error(error.response?.data?.error || 'Failed to fetch device');
    }
  }
};

export const recordings = {
  getAll: async (params) => {
    try {
      return await api.get('/recordings', { params });
    } catch (error) {
      throw new Error(error.response?.data?.error || 'Failed to fetch recordings');
    }
  },
  delete: async (id) => {
    try {
      return await api.delete(`/recordings/${id}`);
    } catch (error) {
      throw new Error(error.response?.data?.error || 'Failed to delete recording');
    }
  }
};

export const getRecordingUrl = (filePath) => `${API_URL}/${filePath}`;

export default api;
