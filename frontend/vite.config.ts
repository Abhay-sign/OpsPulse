import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * In dev, Vite proxies /api to the Spring Boot backend, so the browser only
 * ever talks to one origin. Same idea as nginx in the Docker image: no CORS
 * configuration needed anywhere, because nothing is ever cross-origin.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'node',
  },
});
