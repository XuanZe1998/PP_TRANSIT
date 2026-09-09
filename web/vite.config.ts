import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import ElementPlus from 'unplugin-element-plus/vite'

// https://vitejs.dev/config/
export default defineConfig(({ mode, command }) => {
  const envDir = path.resolve(__dirname, '../config')
  const env = loadEnv(mode, envDir, '')
  const productionBuild = command === 'build' && mode === 'production'

  return {
    // config/.env.local contains workstation-only addresses and Vite loads it
    // for every mode by default. Production builds must use an explicitly
    // supplied VITE_API_BASE_URL or the hostname-aware fallback in http.ts.
    envDir: productionBuild ? false : envDir,
    plugins: [
      vue(),
      AutoImport({ resolvers: [ElementPlusResolver()], dts: command === 'serve' ? 'src/auto-imports.d.ts' : false }),
      Components({ resolvers: [ElementPlusResolver()], dts: command === 'serve' ? 'src/components.d.ts' : false }),
      ElementPlus(),
    ],
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
            const moduleId = id.replace(/\\/g, '/')
            if (moduleId.includes('/node_modules/axios/')) {
              return 'axios'
            }
            if (/\/node_modules\/(?:vue|vue-router|@vue\/[^/]+)\//.test(moduleId)) {
              return 'vue'
            }
          }
        }
      }
    },
    test: {
      exclude: ['tests/e2e/**', 'node_modules/**', 'dist/**'],
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
