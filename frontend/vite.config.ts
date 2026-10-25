import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// API идёт напрямую в Spring, а вход через nginx направляется в Keycloak.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/auth': { target: 'http://localhost:80', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
});
