import { existsSync, writeFileSync, mkdirSync, chmodSync } from 'node:fs'
import { resolve } from 'node:path'
import { randomBytes } from 'node:crypto'
import { createInterface } from 'node:readline/promises'
import { root } from './env.mjs'
const destination = resolve(root, '.env')
if (existsSync(destination)) {
  console.log('.env 已存在，未覆盖。修改本地设置后重新运行相应命令。')
  process.exit(0)
}
const generated = process.argv.includes('--generated')
const secret = () => randomBytes(32).toString('hex')
const username = value => /^[a-zA-Z0-9_-]{3,32}$/.test(value)
async function hidden(question) {
  if (!process.stdin.isTTY) throw new Error('请在交互终端设置口令，自动隔离验证可使用 --generated。')
  process.stdout.write(question)
  process.stdin.setRawMode(true)
  process.stdin.resume()
  let answer = ''
  return new Promise((resolveAnswer, reject) => {
    const read = data => {
      for (const character of data.toString()) {
        if (character === '\u0003') { cleanup(); reject(new Error('已取消')); return }
        if (character === '\r' || character === '\n') { cleanup(); resolveAnswer(answer); return }
        if (character === '\u007f' || character === '\b') answer = answer.slice(0, -1)
        else if (character >= ' ') answer += character
      }
    }
    const cleanup = () => {
      process.stdin.off('data', read)
      process.stdin.setRawMode(false)
      process.stdin.pause()
      process.stdout.write('\n')
    }
    process.stdin.on('data', read)
  })
}
try {
  let admin, user, adminPassword, userPassword
  if (generated) {
    admin = 'local_admin'; user = 'local_student'
    adminPassword = secret(); userPassword = secret()
  } else {
    const rl = createInterface({ input: process.stdin, output: process.stdout })
    admin = (await rl.question('设置本地管理员用户名（3–32 位字母、数字或下划线）: ')).trim()
    user = (await rl.question('设置本地学生用户名（与管理员不同）: ')).trim()
    rl.close()
    if (!username(admin) || !username(user) || admin === user) throw new Error('用户名格式不正确或重复。')
    adminPassword = await hidden('设置管理员口令（12–64 位，不回显）: ')
    userPassword = await hidden('设置学生口令（12–64 位，不回显）: ')
    for (const value of [adminPassword, userPassword]) if (value.length < 12 || Buffer.byteLength(value, 'utf8') > 72 || value.length > 64 || /[\r\n\0]/.test(value)) throw new Error('口令需12–64位且UTF-8不超过72字节。')
  }
  // Node parseEnv and Docker Compose both support single-quoted literals.
  const quote = value => {
    if (value.includes("'")) throw new Error('本地环境文件口令暂不支持单引号，请换一个口令。')
    return `'${value}'`
  }
  const env = {
    DB_HOST:'127.0.0.1', DB_PORT:'3308', DB_NAME:'campus_loop_dev', DB_USERNAME:'campus_loop',
    DB_PASSWORD:secret(), MYSQL_ROOT_PASSWORD:secret(), JWT_SECRET:secret(), SERVER_PORT:'8088',
    UPLOAD_DIR:'.local/uploads', CAMPUS_BOOTSTRAP_ENABLED:'false',
    CAMPUS_ADMIN_USERNAME:admin, CAMPUS_ADMIN_PASSWORD:adminPassword,
    CAMPUS_USER_USERNAME:user, CAMPUS_USER_PASSWORD:userPassword,
  }
  writeFileSync(destination, '# 本机专用，不提交、不分享、不粘贴到聊天。\n' + Object.entries(env).map(([key,value]) => `${key}=${quote(value)}`).join('\n') + '\n', {flag:'wx',mode:0o600})
  chmodSync(destination, 0o600)
  mkdirSync(resolve(root,'.local/uploads'), { recursive:true })
  console.log('已创建本地 .env。数据库及 JWT 密钥随机生成，账号口令仅保存在本机，未输出。')
} catch (error) { console.error(error.message); process.exitCode = 1 }
