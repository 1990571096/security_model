import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

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
      '/api': { target: 'http://localhost:8100', changeOrigin: true },
      '/auth': { target: 'http://localhost:8100', changeOrigin: true }
    }
  }
})
