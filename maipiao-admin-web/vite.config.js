import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    // 5274, next to the customer app's 5273 and clear of the other projects
    // running on this machine.
    port: 5274,
    host: true,
    strictPort: true,

    // Through the gateway like everything else. The admin API is not special:
    // it needs the same JWT handling and the same role check, and talking to
    // movie-service directly would skip both.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      }
    }
  }
})
