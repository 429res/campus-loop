import { defineConfig, loadEnv } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const target = env.VITE_API_PROXY || 'http://127.0.0.1:8088'
  const port = Number(env.VITE_DEV_PORT || 5175)
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('VITE_DEV_PORT must be an integer between 1 and 65535')
  return {
    plugins: [uni()],
    server: { host: '127.0.0.1', port, strictPort: true, proxy: { '/api': { target }, '/uploads': { target } } },
  }
})
