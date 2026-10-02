import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    // 5274，紧挨着用户端的 5273，同时避开这台机器上跑着的其他项目。
    port: 5274,
    host: true,
    strictPort: true,

    // 和其他所有请求一样走网关。管理接口没有特殊待遇：它需要同一套 JWT 处理、
    // 同一道角色校验，而直接连 movie-service 会把这两样都跳过去。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      }
    }
  }
})
