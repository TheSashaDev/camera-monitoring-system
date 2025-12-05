import axios from 'axios';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:3000';

const api = axios.create({
  baseURL: `${API_URL}/api`
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401 || error.response?.status === 403) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export const auth = {
  login: (username, password) => api.post('/auth/login', { username, password }),
  verify: () => api.get('/auth/verify')
};

export const devices = {
  getAll: () => api.get('/devices'),
  get: (id) => api.get(`/devices/${id}`)
};

export const recordings = {
  getAll: (params) => api.get('/recordings', { params }),
  delete: (id) => api.delete(`/recordings/${id}`)
};

export const getRecordingUrl = (filePath) => `${API_URL}/${filePath}`;

export default api;
