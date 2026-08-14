import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 后端不在 8080 时，用 SALMON_SERVER_URL 指定代理目标
const serverUrl = process.env.SALMON_SERVER_URL ?? 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: serverUrl,
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    proxy: {
      '/api': {
        target: serverUrl,
        changeOrigin: true,
      },
    },
  },
})
