import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  base: '/mobile-ai/rest/',
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'https://admin.cretaceousfuture.com',
        changeOrigin: true,
        secure: true,
      },
    },
  },
  preview: {
    proxy: {
      '/api': {
        target: 'https://admin.cretaceousfuture.com',
        changeOrigin: true,
        secure: true,
      },
    },
  },
})
