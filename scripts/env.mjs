import { readFileSync, existsSync } from 'node:fs'
import { parseEnv } from 'node:util'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
export const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export function localEnv(required = true) {
  const file = resolve(root, '.env')
  if (!existsSync(file) && required) throw new Error('缺少根目录 .env，请先运行 node scripts/setup.mjs')
  const local = existsSync(file) ? parseEnv(readFileSync(file, 'utf8')) : {}
  const env = { ...local, ...process.env }
  env.UPLOAD_DIR = resolve(root, env.UPLOAD_DIR || '.local/uploads')
  return env
}
