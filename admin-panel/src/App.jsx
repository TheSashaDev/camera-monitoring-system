import React, { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate, NavLink, useNavigate } from 'react-router-dom';
import { auth } from './api';
import { initSocket, disconnectSocket, getSocket } from './socket';
import Dashboard from './pages/Dashboard';
import Devices from './pages/Devices';
import Recordings from './pages/Recordings';
import Login from './pages/Login';

const AuthContext = React.createContext(null);

export const useAuth = () => React.useContext(AuthContext);

function ProtectedRoute({ children }) {
  const { isAuthenticated, loading } = useAuth();
  
  if (loading) {
    return <div className="login-container"><div>Loading...</div></div>;
  }
  
  return isAuthenticated ? children : <Navigate to="/login" />;
}

function Layout({ children }) {
  const { logout } = useAuth();
  
  return (
    <div className="app">
      <aside className="sidebar">
        <h1>📹 Surveillance</h1>
        <nav>
          <NavLink to="/" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            Dashboard
          </NavLink>
          <NavLink to="/devices" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            Devices
          </NavLink>
          <NavLink to="/recordings" className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            Recordings
          </NavLink>
        </nav>
        <div style={{ marginTop: 'auto', paddingTop: '20px' }}>
          <button onClick={logout} className="btn btn-secondary" style={{ width: '100%' }}>
            Logout
          </button>
        </div>
      </aside>
      <main className="main-content">
        {children}
      </main>
    </div>
  );
}

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [loading, setLoading] = useState(true);
  const [devices, setDevices] = useState([]);

  useEffect(() => {
    const token = localStorage.getItem('token');
    if (token) {
      auth.verify()
        .then(() => {
          setIsAuthenticated(true);
          const socket = initSocket(token);
          setupSocketListeners(socket);
        })
        .catch(() => {
          localStorage.removeItem('token');
          setIsAuthenticated(false);
        })
        .finally(() => setLoading(false));
    } else {
      setLoading(false);
    }

    return () => disconnectSocket();
  }, []);

  const setupSocketListeners = (socket) => {
    socket.on('admin:registered', (data) => {
      console.log('Admin registered:', data);
      setDevices(data.devices || []);
    });

    socket.on('device:online', (data) => {
      setDevices(prev => {
        const exists = prev.find(d => d.deviceId === data.deviceId);
        if (exists) {
          return prev.map(d => d.deviceId === data.deviceId ? { ...d, ...data, isOnline: true } : d);
        }
        return [...prev, { ...data, isOnline: true }];
      });
    });

    socket.on('device:offline', (data) => {
      setDevices(prev => prev.map(d => 
        d.deviceId === data.deviceId ? { ...d, isOnline: false } : d
      ));
    });

    socket.on('device:recording_started', (data) => {
      setDevices(prev => prev.map(d => 
        d.deviceId === data.deviceId ? { ...d, isRecording: true } : d
      ));
    });

    socket.on('device:recording_stopped', (data) => {
      setDevices(prev => prev.map(d => 
        d.deviceId === data.deviceId ? { ...d, isRecording: false } : d
      ));
    });

    socket.on('device:recording_status', (data) => {
      setDevices(prev => prev.map(d => 
        d.deviceId === data.deviceId ? { ...d, isRecording: data.isRecording } : d
      ));
    });
  };

  const login = async (username, password) => {
    const response = await auth.login(username, password);
    const { token } = response.data;
    localStorage.setItem('token', token);
    setIsAuthenticated(true);
    const socket = initSocket(token);
    setupSocketListeners(socket);
    return response.data;
  };

  const logout = () => {
    localStorage.removeItem('token');
    disconnectSocket();
    setIsAuthenticated(false);
    setDevices([]);
  };

  return (
    <AuthContext.Provider value={{ isAuthenticated, loading, login, logout, devices, setDevices }}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={
            isAuthenticated ? <Navigate to="/" /> : <Login />
          } />
          <Route path="/" element={
            <ProtectedRoute>
              <Layout><Dashboard /></Layout>
            </ProtectedRoute>
          } />
          <Route path="/devices" element={
            <ProtectedRoute>
              <Layout><Devices /></Layout>
            </ProtectedRoute>
          } />
          <Route path="/recordings" element={
            <ProtectedRoute>
              <Layout><Recordings /></Layout>
            </ProtectedRoute>
          } />
        </Routes>
      </BrowserRouter>
    </AuthContext.Provider>
  );
}

export default App;
