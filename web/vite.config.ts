import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const envDir = path.resolve(__dirname, '../config')
  const env = loadEnv(mode, envDir, '')

  return {
    envDir,
    plugins: [vue()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    build: {
      chunkSizeWarningLimit: 900,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) {
              return 'element'
            }
            if (id.includes('node_modules/axios')) {
              return 'axios'
            }
            if (id.includes('node_modules/vue') || id.includes('node_modules/vue-router')) {
              return 'vue'
            }
          }
        }
      }
    },
    server: {
      host: env.VITE_DEV_HOST || '127.0.0.1',
      port: Number(env.VITE_DEV_PORT || 5173),
      strictPort: true,
      allowedHosts: (env.VITE_ALLOWED_HOSTS || 'linknux.com,api.linknux.com')
        .split(',')
        .map((host) => host.trim())
        .filter(Boolean),
      proxy: {
        '/api': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:8089',
          changeOrigin: true,
          rewrite: (requestPath) => requestPath.replace(/^\/api/, ''),
        },
        '/webhooks': {
          target: env.VITE_API_PROXY_TARGET || 'http://localhost:8089',
          changeOrigin: true,
        },
      },
    },
  }
})
