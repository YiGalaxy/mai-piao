import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    // 5273, not the Vite default 5173: another project on this machine
    // (大宗货物交易平台) already serves its frontend on 5173.
    port: 5273,

    // Listen on every interface, not just 127.0.0.1.
    //
    // Vite's default binds to the IPv4 loopback only. On Windows 11 a browser
    // resolving `localhost` tries ::1 first, and with nothing listening there
    // the page simply fails to load - while curl and the dev server's own
    // output both look perfectly healthy. Binding all interfaces accepts both
    // families and also makes the app reachable from a phone on the same
    // network, which is useful for checking the layout.
    host: true,

    // Fail loudly instead of quietly moving to the next free port.
    //
    // Vite's default behaviour is to pick another port and carry on, which
    // looks harmless and is not: the API proxy in this very file points at
    // the gateway, so a silent port change means requests go somewhere that
    // still answers - the other project's backend - and the responses look
    // plausible enough to waste an hour on.
    strictPort: true,

    // The dev server proxies /api to the gateway, so the browser sees a
    // same-origin request and CORS never enters the picture during
    // development. In production Nginx does the same thing.
    //
    // Note this targets the gateway (:9000), not a service directly. The
    // frontend never talks to a service that has not been through the edge,
    // which is what keeps the identity headers trustworthy.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9000',
        changeOrigin: true
      }
    }
  }
})
