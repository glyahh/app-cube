import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// export default defineConfig({
//   plugins: [
//     vue(),
//     vueDevTools(),
//   ],
//   resolve: {
//     alias: {
//       '@': fileURLToPath(new URL('./src', import.meta.url))
//     },
//   },
// })

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue(), ...(env.VITE_ENABLE_DEVTOOLS === 'true' ? [vueDevTools()] : [])],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // 固定开发服务器监听 IPv4，避免 localhost 在不同环境下解析到 IPv6。
      host: '127.0.0.1',
      proxy: {
        '/api': {
          target: 'http://localhost:8124',
          changeOrigin: true,
          secure: false,
          // Agent 默认走 WebSocket（/api/ws/agent），代理必须开启升级转发，
          // 否则握手会被 Vite 当成普通 HTTP 请求丢掉。SSE 降级路径不受影响。
          ws: true,
          // 避免后端返回绝对重定向（Location 指向 8124）导致 iframe 真正跳到跨域源
          configure: (proxy) => {
            proxy.on('proxyRes', (proxyRes) => {
              const loc = proxyRes.headers.location
              if (!loc) return
              const locStr = Array.isArray(loc) ? loc[0] : String(loc)
              try {
                const u = new URL(locStr)
                if (u.host === 'localhost:8124') {
                  proxyRes.headers.location = u.pathname + u.search + u.hash
                }
              } catch {
                // 非绝对 URL（相对跳转）不处理
              }
            })
          },
        },
        // 预览静态站点：保持 iframe 同源（通过 Vite 反向代理到后端静态目录）
        '/static': {
          target: 'http://localhost:8124',
          changeOrigin: true,
          secure: false,
          configure: (proxy) => {
            proxy.on('proxyRes', (proxyRes) => {
              const loc = proxyRes.headers.location
              if (!loc) return
              const locStr = Array.isArray(loc) ? loc[0] : String(loc)
              try {
                const u = new URL(locStr)
                if (u.host === 'localhost:8124') {
                  proxyRes.headers.location = u.pathname + u.search + u.hash
                }
              } catch {
                // ignore
              }
            })
          },
        },
      },
    },
  }
})
