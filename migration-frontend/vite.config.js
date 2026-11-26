import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const backend = process.env.SECURITY_MODEL_VITE_BACKEND || 'http://localhost:8100'

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue']
        }
      }
    }
  },
  server: {
    port: 8014,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/auth': { target: backend, changeOrigin: true }
    }
  }
})
