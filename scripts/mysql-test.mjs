// Creates an isolated disposable MySQL test service with random, never-printed credentials.
import { execFileSync } from 'node:child_process'
import { spawnCommand } from './process.mjs'
import { randomBytes } from 'node:crypto'
import { root } from './env.mjs'
import { resolve } from 'node:path'
const container = `campus-loop-test-${randomBytes(4).toString('hex')}`
const port = process.env.CAMPUS_TEST_PORT || '3319'
if (!/^\d+$/.test(port)) throw new Error('Invalid test port')
const env = {...process.env, MYSQL_DATABASE:'campus_loop_ci_test', MYSQL_USER:'campus_test', MYSQL_PASSWORD:randomBytes(32).toString('hex'), MYSQL_ROOT_PASSWORD:randomBytes(32).toString('hex')}
if (process.platform === 'darwin' && !env.JAVA_HOME) env.JAVA_HOME = execFileSync('/usr/libexec/java_home',['-v','17'],{encoding:'utf8'}).trim()
const run = (command,args,cwd=root) => new Promise((ok,fail)=>{
  const proc=spawnCommand(command,args,{cwd,env,stdio:'inherit'})
  proc.on('error',fail); proc.on('exit',code=>code===0?ok():fail(new Error(`Test command exit ${code}`)))
})
let started=false
try {
  await run('docker',['run','--detach','--rm','--name',container,'-p',`127.0.0.1:${port}:3306`,'--env','MYSQL_DATABASE','--env','MYSQL_USER','--env','MYSQL_PASSWORD','--env','MYSQL_ROOT_PASSWORD','--health-cmd','mysqladmin ping -h localhost --silent','--health-interval','2s','--health-retries','60','mysql:8.4.11'])
  started=true
  let healthy=false
  for(let tries=0;tries<90;tries++) {
    const state=execFileSync('docker',['inspect','--format','{{.State.Health.Status}}',container],{encoding:'utf8'}).trim()
    if(state==='healthy'){healthy=true;break}
    if(state==='unhealthy')throw new Error('MySQL test service failed health check')
    await new Promise(done=>setTimeout(done,1000))
  }
  if(!healthy)throw new Error('MySQL test startup timeout')
  env.TEST_DB_URL=`jdbc:mysql://127.0.0.1:${port}/campus_loop_ci_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
  env.TEST_DB_USERNAME=env.MYSQL_USER;env.TEST_DB_PASSWORD=env.MYSQL_PASSWORD
  await run(process.platform==='win32'?'mvnw.cmd':'./mvnw',['-B','-ntp','-Dcampus.mysql-test=true','test'],resolve(root,'sys-project'))
} catch(error){console.error(error.message);process.exitCode=1}
finally{if(started)await run('docker',['stop',container])}
