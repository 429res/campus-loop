import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = env.VITE_API_PROXY || 'http://127.0.0.1:8088'
  return {
    plugins: [uni()],
    server: { host: '127.0.0.1', port: 5175, strictPort: true, proxy: { '/api': { target }, '/uploads': { target } } },
  }
})
