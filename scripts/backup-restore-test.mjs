import { createHash, randomBytes } from 'node:crypto'
import { createReadStream, createWriteStream, existsSync, mkdtempSync, readdirSync, rmSync } from 'node:fs'
import { cp, mkdir, readFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { basename, join, relative, resolve, sep } from 'node:path'
import { spawnCommand } from './process.mjs'
import { root } from './env.mjs'

const image = 'mysql:8.4.11'
const backend = resolve(root, 'sys-project')
const wrapper = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw'
const prefix = `campus-loop-backup-drill-${process.pid}-${Date.now()}`
const sourceContainer = `${prefix}-source`
const targetContainer = `${prefix}-target`
const sourceDatabase = 'campus_loop_backup_source_test'
const targetDatabase = 'campus_loop_backup_restore_test'
const databaseUser = 'campus_backup_drill'
const databasePassword = randomBytes(24).toString('base64url')
const rootPassword = randomBytes(24).toString('base64url')
const userPassword = randomBytes(24).toString('base64url')
const adminPassword = randomBytes(24).toString('base64url')
const sourceJwt = randomBytes(48).toString('base64url')
const targetJwt = randomBytes(48).toString('base64url')
const work = mkdtempSync(join(tmpdir(), 'campus-loop-backup-drill-'))
const sourceUploads = join(work, 'source', 'uploads')
const targetUploads = join(work, 'target', 'uploads')
const dumpFile = join(work, 'backup.sql')
const containers = new Set()
const applications = new Set()

function execute(command, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawnCommand(command, args, {
      cwd: options.cwd || root,
      env: options.env || process.env,
      stdio: options.stdio || ['ignore', 'pipe', 'pipe']
    })
    let stdout = ''
    let stderr = ''
    child.stdout?.on('data', chunk => { stdout += chunk })
    child.stderr?.on('data', chunk => { stderr += chunk })
    child.once('error', reject)
    child.once('exit', code => code === 0
      ? resolvePromise(stdout.trim())
      : reject(new Error(`${command} 未完成（exit ${code}）${stderr.trim() ? `：${stderr.trim().split(/\r?\n/).at(-1)}` : ''}`)))
  })
}

async function pipeProcess(command, args, input, output) {
  await new Promise((resolvePromise, reject) => {
    const child = spawnCommand(command, args, { cwd: root, stdio: ['pipe', 'pipe', 'pipe'] })
    let stderr = ''
    child.stderr.on('data', chunk => { stderr += chunk })
    if (input) createReadStream(input).pipe(child.stdin)
    else child.stdin.end()
    if (output) child.stdout.pipe(createWriteStream(output))
    else child.stdout.resume()
    child.once('error', reject)
    child.once('exit', code => code === 0
      ? resolvePromise()
      : reject(new Error(`${command} 未完成（exit ${code}）${stderr.trim() ? `：${stderr.trim().split(/\r?\n/).at(-1)}` : ''}`)))
  })
}

async function freePort() {
  return new Promise((resolvePromise, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(error => error ? reject(error) : resolvePromise(port))
    })
  })
}

async function retry(label, action, attempts = 90) {
  let last
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try { return await action() } catch (error) { last = error }
    await new Promise(resolvePromise => setTimeout(resolvePromise, 1000))
  }
  throw new Error(`${label} 未就绪：${last?.message || '超时'}`)
}

async function startDatabase(name, database, port) {
  await execute('docker', [
    'run', '--rm', '-d', '--name', name,
    '-p', `127.0.0.1:${port}:3306`,
    '--env', `MYSQL_DATABASE=${database}`,
    '--env', `MYSQL_USER=${databaseUser}`,
    '--env', `MYSQL_PASSWORD=${databasePassword}`,
    '--env', `MYSQL_ROOT_PASSWORD=${rootPassword}`,
    image
  ])
  containers.add(name)
  await retry(`${name} MySQL`, () => execute('docker', [
    'exec', '--env', `MYSQL_PWD=${databasePassword}`, name,
    'mysqladmin', 'ping', '-h127.0.0.1', `-u${databaseUser}`, '--silent'
  ]))
}

function jarPath() {
  const target = resolve(backend, 'sys-project-api', 'target')
  const name = existsSync(target) && readdirSync(target).find(file => file.endsWith('.jar') && !file.endsWith('.original'))
  if (!name) throw new Error('后端构建完成后未找到可运行 jar')
  return resolve(target, name)
}

function startApplication(port, databasePort, database, uploadDirectory, jwt, bootstrap) {
  const env = {
    ...process.env,
    DB_HOST: '127.0.0.1', DB_PORT: String(databasePort), DB_NAME: database,
    DB_USERNAME: databaseUser, DB_PASSWORD: databasePassword,
    JWT_SECRET: jwt, SERVER_PORT: String(port), UPLOAD_DIR: uploadDirectory,
    CAMPUS_BOOTSTRAP_ENABLED: String(bootstrap), CAMPUS_BOOTSTRAP_ONLY: 'false',
    CAMPUS_DEMO_ENABLED: 'false', CAMPUS_REGISTRATION_MODE: 'CLOSED',
    CAMPUS_ADMIN_USERNAME: 'backup_admin', CAMPUS_ADMIN_PASSWORD: adminPassword,
    CAMPUS_USER_USERNAME: 'backup_student', CAMPUS_USER_PASSWORD: userPassword,
    CAMPUS_EXCHANGE_EXPIRY_ENABLED: 'false'
  }
  const child = spawnCommand('java', ['-jar', jarPath()], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] })
  let recent = ''
  const remember = chunk => { recent = (recent + chunk).slice(-8000) }
  child.stdout.on('data', remember)
  child.stderr.on('data', remember)
  applications.add(child)
  child.once('exit', () => applications.delete(child))
  child.recentOutput = () => recent
  return child
}

function lastApplicationLine(child) {
  const safe = child.recentOutput()
    .replaceAll(databasePassword, '[REDACTED]').replaceAll(rootPassword, '[REDACTED]')
    .replaceAll(userPassword, '[REDACTED]').replaceAll(adminPassword, '[REDACTED]')
    .replaceAll(sourceJwt, '[REDACTED]').replaceAll(targetJwt, '[REDACTED]')
  const lines = safe.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  const causes = lines.filter(line => /Caused by:|APPLICATION FAILED|Description:|ERROR/.test(line))
  return (causes.length ? causes : lines).slice(-4).join(' / ') || '进程已退出'
}

async function stopApplication(child) {
  if (!applications.has(child)) return
  await new Promise(resolvePromise => {
    const timer = setTimeout(() => { if (applications.has(child)) child.kill('SIGKILL') }, 10000)
    child.once('exit', () => { clearTimeout(timer); resolvePromise() })
    child.kill('SIGTERM')
  })
}

async function api(base, path, options = {}) {
  const headers = { ...(options.body && !(options.body instanceof FormData) ? { 'content-type': 'application/json' } : {}), ...options.headers }
  const response = await fetch(`${base}${path}`, {
    method: options.method || 'GET', headers,
    body: options.body instanceof FormData ? options.body : options.body ? JSON.stringify(options.body) : undefined
  })
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.includes('application/json')) {
    if (!response.ok) throw new Error(`${path} 返回 HTTP ${response.status}`)
    return Buffer.from(await response.arrayBuffer())
  }
  const envelope = await response.json()
  if (!response.ok || envelope.code !== 200) throw new Error(`${path} 返回 HTTP ${response.status}：${envelope.msg || '未知错误'}`)
  return envelope.data
}

function auth(token) { return { authorization: `Bearer ${token}` } }

async function login(base, username, password) {
  return api(base, '/api/auth/login', { method: 'POST', body: { username, password } })
}

async function migrationState(container, database) {
  const value = await execute('docker', [
    'exec', '--env', `MYSQL_PWD=${databasePassword}`, container,
    'mysql', '-N', '-B', `-u${databaseUser}`, database, '-e',
    "SELECT CONCAT(COUNT(*),'|',COALESCE(MAX(CAST(version AS UNSIGNED)),0),'|',SUM(success=0)) FROM flyway_schema_history"
  ])
  const [count, latest, failed] = value.split('|').map(Number)
  return { count, latest, failed }
}

async function hashFile(path) {
  return createHash('sha256').update(await readFile(path)).digest('hex')
}

async function cleanup() {
  for (const child of [...applications]) await stopApplication(child).catch(() => {})
  for (const container of [...containers]) await execute('docker', ['stop', container]).catch(() => {})
  const tempRoot = resolve(tmpdir())
  const resolvedWork = resolve(work)
  const rel = relative(tempRoot, resolvedWork)
  if (basename(resolvedWork).startsWith('campus-loop-backup-drill-') && rel && !rel.startsWith(`..${sep}`) && rel !== '..') {
    rmSync(resolvedWork, { recursive: true, force: true })
  } else {
    throw new Error(`拒绝清理未验证的临时目录：${resolvedWork}`)
  }
}

let sourceApp
let targetApp
try {
  console.log('A-05 备份恢复演练：创建一次性隔离环境（不会读取根 .env）')
  await execute('docker', ['version'])
  await execute(wrapper, ['-B', '-ntp', '-DskipTests', 'clean', 'package'], { cwd: backend, stdio: 'inherit' })
  await mkdir(sourceUploads, { recursive: true })
  const [sourceDbPort, targetDbPort, sourceAppPort, targetAppPort] = await Promise.all([freePort(), freePort(), freePort(), freePort()])

  await startDatabase(sourceContainer, sourceDatabase, sourceDbPort)
  sourceApp = startApplication(sourceAppPort, sourceDbPort, sourceDatabase, sourceUploads, sourceJwt, true)
  const sourceBase = `http://127.0.0.1:${sourceAppPort}`
  await retry('源应用', async () => {
    if (sourceApp.exitCode !== null) throw new Error(lastApplicationLine(sourceApp))
    const health = await api(sourceBase, '/api/health')
    if (health.database !== 'UP') throw new Error('数据库健康检查未通过')
  })

  // The web server can answer health while the ApplicationRunner is still hashing bootstrap passwords.
  const student = await retry('源学生账号', () => login(sourceBase, 'backup_student', userPassword), 30)
  const admin = await retry('源管理员账号', () => login(sourceBase, 'backup_admin', adminPassword), 30)
  const categories = await api(sourceBase, '/api/categories')
  if (categories.length < 2) throw new Error('演练需要至少两个可用分类')

  const form = new FormData()
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64')
  form.append('file', new Blob([png], { type: 'image/png' }), 'fictional-backup-drill.png')
  const uploaded = await api(sourceBase, '/api/uploads', { method: 'POST', headers: auth(student.token), body: form })
  const item = await api(sourceBase, '/api/items', {
    method: 'POST', headers: auth(student.token),
    body: {
      title: '备份演练虚构教材', description: '仅用于 A-05 隔离恢复演练的虚构物品。',
      categoryId: categories[0].id, conditionLevel: 4, tags: ['虚构', '演练'],
      wantedCategoryId: categories[1].id, wantedTags: ['恢复'], imageUrl: uploaded.url
    }
  })
  const approved = await api(sourceBase, `/api/admin/items/${item.id}/review`, {
    method: 'POST', headers: auth(admin.token), body: { version: item.version, decision: 'APPROVE', reason: 'A-05 备份恢复演练虚构审核' }
  })
  const demand = await api(sourceBase, '/api/demands', {
    method: 'POST', headers: auth(student.token),
    body: { categoryId: categories[1].id, description: 'A-05 备份恢复演练虚构需求。', preferredTags: ['恢复'], offeredItemIds: [approved.id] }
  })
  const evidenceForm = new FormData()
  evidenceForm.append('file', new Blob([png], { type: 'image/png' }), 'fictional-private-evidence.png')
  const evidenceUpload = await api(sourceBase, '/api/uploads/evidence', { method: 'POST', headers: auth(student.token), body: evidenceForm })
  const history = await api(sourceBase, `/api/items/${item.id}/history`, {
    method: 'POST', headers: auth(student.token),
    body: {
      eventType: 'REPAIR', statement: 'A-05 恢复演练虚构履历。', occurredAt: null, timeUnknown: true,
      relatedExchangeId: null, correctsEventId: null, evidenceUploadIds: [evidenceUpload.uploadId]
    }
  })
  const sourceUploadPath = join(sourceUploads, uploaded.url.split('/').at(-1))
  const sourceUploadHash = await hashFile(sourceUploadPath)
  const sourceEvidenceHash = await hashFile(join(`${sourceUploads}-evidence`, `${evidenceUpload.uploadId}.png`))
  const sourceMigrations = await migrationState(sourceContainer, sourceDatabase)
  if (sourceMigrations.failed !== 0) throw new Error('源库存在失败的 Flyway 迁移')

  await stopApplication(sourceApp)
  await pipeProcess('docker', [
    'exec', '--env', `MYSQL_PWD=${databasePassword}`, sourceContainer,
    'mysqldump', `-u${databaseUser}`, '--single-transaction', '--routines', '--triggers', '--no-tablespaces', sourceDatabase
  ], null, dumpFile)
  await cp(sourceUploads, targetUploads, { recursive: true })
  const sourceEvidence = `${sourceUploads}-evidence`
  if (existsSync(sourceEvidence)) await cp(sourceEvidence, `${targetUploads}-evidence`, { recursive: true })

  await startDatabase(targetContainer, targetDatabase, targetDbPort)
  await pipeProcess('docker', [
    'exec', '-i', '--env', `MYSQL_PWD=${databasePassword}`, targetContainer,
    'mysql', `-u${databaseUser}`, targetDatabase
  ], dumpFile, null)

  targetApp = startApplication(targetAppPort, targetDbPort, targetDatabase, targetUploads, targetJwt, false)
  const targetBase = `http://127.0.0.1:${targetAppPort}`
  await retry('恢复应用', async () => {
    if (targetApp.exitCode !== null) throw new Error(lastApplicationLine(targetApp))
    const health = await api(targetBase, '/api/health')
    if (health.database !== 'UP') throw new Error('数据库健康检查未通过')
  })

  const restoredLogin = await retry('恢复账号', () => login(targetBase, 'backup_student', userPassword), 30)
  const restoredItem = await api(targetBase, `/api/items/mine/${item.id}`, { headers: auth(restoredLogin.token) })
  const restoredDemand = await api(targetBase, `/api/demands/${demand.id}`, { headers: auth(restoredLogin.token) })
  const restoredHistory = await api(targetBase, `/api/items/${item.id}/history/${history.id}`, { headers: auth(restoredLogin.token) })
  const restoredImage = await api(targetBase, uploaded.url)
  const restoredEvidence = await api(targetBase, `/api/history-evidence/${evidenceUpload.uploadId}`, { headers: auth(restoredLogin.token) })
  const restoredUploadHash = createHash('sha256').update(restoredImage).digest('hex')
  const restoredEvidenceHash = createHash('sha256').update(restoredEvidence).digest('hex')
  const targetMigrations = await migrationState(targetContainer, targetDatabase)
  if (restoredItem.title !== item.title || restoredItem.imageUrl !== uploaded.url || restoredItem.status !== 'AVAILABLE') throw new Error('恢复后的物品字段不一致')
  if (restoredDemand.description !== demand.description || !restoredDemand.offeredItems?.some(offered => offered.itemId === item.id)) throw new Error('恢复后的需求或物品引用不一致')
  if (restoredHistory.statement !== history.statement || !restoredHistory.evidence?.some(evidence => evidence.uploadId === evidenceUpload.uploadId)) throw new Error('恢复后的履历或私有证据引用不一致')
  if (restoredUploadHash !== sourceUploadHash) throw new Error('恢复后的上传文件摘要不一致')
  if (restoredEvidenceHash !== sourceEvidenceHash) throw new Error('恢复后的私有证据文件摘要不一致')
  if (targetMigrations.failed !== 0 || JSON.stringify(targetMigrations) !== JSON.stringify(sourceMigrations)) throw new Error('恢复后的 Flyway 状态不一致')

  console.log(`通过：新隔离库恢复后应用可读取虚构物品 #${item.id} 与需求 #${demand.id}`)
  console.log(`通过：公开上传引用 ${uploaded.url} 可读取，SHA-256 ${restoredUploadHash}`)
  console.log(`通过：私有履历证据 #${evidenceUpload.uploadId} 经鉴权可读取，SHA-256 ${restoredEvidenceHash}`)
  console.log(`通过：Flyway 成功迁移 ${targetMigrations.count} 条，最新版本 V${targetMigrations.latest}，失败 0 条`)
  console.log('通过：源库、恢复库、应用、备份文件与上传副本均位于一次性隔离环境')
} catch (error) {
  console.error(`演练失败：${error.message}`)
  process.exitCode = 1
} finally {
  try {
    await cleanup()
    console.log('清理完成：一次性容器和操作系统临时目录已移除')
  } catch (error) {
    console.error(`清理失败：${error.message}`)
    process.exitCode = 1
  }
}
