import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    // 用 5273 而不是 Vite 默认的 5173：这台机器上另一个项目
    // （大宗货物交易平台）的前端已经占了 5173。
    port: 5273,

    // 监听所有网卡，不只是 127.0.0.1。
    //
    // Vite 默认只绑 IPv4 回环。Windows 11 上浏览器解析 `localhost` 会先试 ::1，
    // 而那里没有东西在监听，页面就是打不开 —— 与此同时 curl 和 dev server 自己的
    // 输出都显示一切正常。绑所有网卡能把两个协议族一起接住，顺带让同一网络里的
    // 手机也能访问，检查布局时用得上。
    host: true,

    // 端口被占就直接失败，而不是悄悄换一个继续跑。
    //
    // Vite 默认的行为是换一个空闲端口接着跑，看着无害，其实不是：这个文件里的
    // API 代理指向网关，端口一换，请求就发到了别处 —— 而那个别处（另一个项目的
    // 后端）照样会应答，返回的东西看起来还挺像回事，能白搭进去一个小时。
    strictPort: true,

    // dev server 把 /api 代理到网关，这样浏览器看到的是同源请求，
    // 开发期就完全不会碰到 CORS。生产环境里 Nginx 做同样的事。
    //
    // 注意目标是网关（:9000），不是某个服务。前端从不直接和没经过边缘的服务说话，
    // 这正是身份头可信的原因。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      }
    }
  }
})
