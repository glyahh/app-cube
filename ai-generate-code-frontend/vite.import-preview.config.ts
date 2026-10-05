import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 独立预览没有生产代理，也不加入生产构建入口。
export default defineConfig({
  plugins: [vue(), {
    name: 'import-preview-entry',
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const pathname = request.url?.split('?')[0]
        if (pathname === '/' || pathname === '/index.html') {
          response.writeHead(302, { Location: '/import-preview.html' })
          response.end()
          return
        }
        next()
      })
    },
  }],
  resolve: { alias: [
    { find: '@/utils/agentStreamTransport', replacement: fileURLToPath(new URL('./src/preview/project-import/workbenchTransport.ts', import.meta.url)) },
    { find: '@', replacement: fileURLToPath(new URL('./src', import.meta.url)) },
  ] },
  server: { host: '127.0.0.1', port: 5175, strictPort: true, open: false },
  // 工作台按需加载，先优化已有 Markdown 依赖，避免首次进入时整页重载丢失内存工程。
  optimizeDeps: { include: ['marked'] },
  build: {
    outDir: '../temp/import-frontend-preview/build',
    emptyOutDir: false,
    rollupOptions: { input: fileURLToPath(new URL('./import-preview.html', import.meta.url)) },
  },
})
