import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 8421,
    proxy: {
      '/api': 'http://localhost:8420',
      '/socket.io': {
        target: 'http://localhost:8420',
        ws: true
      },
      '/recordings': 'http://localhost:8420'
    }
  },
  preview: {
    port: 8421
  }
});
