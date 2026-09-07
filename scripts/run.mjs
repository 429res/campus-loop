import { execFileSync } from 'node:child_process'
import { spawnCommand, frontendEnvironment } from './process.mjs'
import { resolve, join } from 'node:path'
import { existsSync, readdirSync } from 'node:fs'
import { root, localEnv } from './env.mjs'
const action = process.argv[2]
const win = process.platform === 'win32'
const backend = resolve(root,'sys-project')
const wrapper = win ? 'mvnw.cmd' : './mvnw'
let env
try { env = localEnv(!['install','build','test','mysql-test','doctor','wechat'].includes(action)) } catch (e) { console.error(e.message); process.exit(1) }
// Prefer the required JDK on macOS when the shell's Java default is newer.
if (process.platform === 'darwin' && !process.env.JAVA_HOME) {
  try { env.JAVA_HOME = execFileSync('/usr/libexec/java_home',['-v','17'],{encoding:'utf8'}).trim() } catch {}
}
if (env.JAVA_HOME) {
  // A copied Windows environment is an ordinary case-sensitive JS object.
  // Preserve the runner's `Path` before normalizing it to one PATH entry.
  const pathKeys = Object.keys(env).filter(key => win ? key.toLowerCase() === 'path' : key === 'PATH')
  const originalPath = env[pathKeys.at(-1)] || ''
  for (const key of pathKeys) delete env[key]
  env.PATH = join(env.JAVA_HOME, 'bin') + (win ? ';' : ':') + originalPath
}
async function run(command, args, cwd = root, extra = {}) {
  return new Promise((ok, fail) => {
    const childEnv = command === 'npm' ? frontendEnvironment(env) : env
    const proc = spawnCommand(command,args,{cwd,env:{...childEnv,...extra},stdio:'inherit'})
    proc.once('error',fail)
    proc.once('exit',code => code === 0 ? ok() : fail(new Error(`命令未完成（exit ${code}）`)))
  })
}
async function packageBackend() { await run(wrapper,['-B','-ntp','-DskipTests','package'],backend) }
function jar() {
  const target = resolve(backend,'sys-project-api/target')
  const name = existsSync(target) && readdirSync(target).find(x => x.endsWith('.jar') && !x.endsWith('.original'))
  if (!name) throw new Error('未找到后端 jar，请运行 node scripts/run.mjs build。')
  return resolve(target,name)
}
try {
  switch(action) {
    case 'install':
      await run('npm',['ci'],resolve(root,'project-self'))
      await run('npm',['ci'],resolve(root,'wx-project-self'))
      break
    case 'db': await run('docker',['compose','up','-d','--wait']); break
    case 'db-stop': await run('docker',['compose','stop']); break
    case 'init':
      await packageBackend()
      await run('java',['-jar',jar()],root,{CAMPUS_BOOTSTRAP_ENABLED:'true',CAMPUS_BOOTSTRAP_ONLY:'true',SPRING_MAIN_WEB_APPLICATION_TYPE:'none'})
      break
    case 'backend': await run('java',['-jar',jar()],root,{CAMPUS_BOOTSTRAP_ENABLED:'false'}); break
    case 'admin': await run('npm',['run','dev','--','--host','127.0.0.1'],resolve(root,'project-self')); break
    case 'h5': await run('npm',['run','dev:h5','--','--host','127.0.0.1'],resolve(root,'wx-project-self')); break
    case 'wechat': await run('npm',['run','build:mp-weixin'],resolve(root,'wx-project-self')); break
    case 'test': await run(wrapper,['-B','-ntp','test'],backend); break
    case 'mysql-test':
      if (!env.TEST_DB_URL || !env.TEST_DB_URL.includes('_test')) throw new Error('mysql-test 只允许显式 TEST_DB_URL 指向 _test 数据库。')
      await run(wrapper,['-B','-ntp','-Dcampus.mysql-test=true','test'],backend)
      break
    case 'build':
      await run('npm',['run','build'],resolve(root,'project-self'))
      await run('npm',['run','build:h5'],resolve(root,'wx-project-self'))
      await run('npm',['run','build:mp-weixin'],resolve(root,'wx-project-self'))
      await run(wrapper,['-B','-ntp','verify'],backend)
      break
    case 'doctor':
      console.log(`Node ${process.versions.node} (required 24.20.0)`)
      await run('npm',['--version'])
      await run('java',['-version'])
      await run(wrapper,['--version'],backend)
      break
    default: throw new Error('用法: node scripts/run.mjs install|db|db-stop|init|backend|admin|h5|wechat|test|mysql-test|build|doctor')
  }
} catch(error) { console.error(error.message); process.exitCode = 1 }
