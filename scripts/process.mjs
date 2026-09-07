import { spawn } from 'node:child_process'

/** Keep native executable argument boundaries on Windows (especially paths with spaces). */
export function spawnCommand(command, args, options = {}) {
  if (process.platform !== 'win32') return spawn(command, args, { ...options, shell: false })
  if (command !== 'npm' && !/\.(cmd|bat)$/i.test(command)) return spawn(command, args, { ...options, shell: false })
  // Our npm / Maven script arguments are fixed CLI flags. Do not interpret arbitrary shell text.
  const executable = command === 'npm' ? 'npm.cmd' : command
  const safe = /^[a-zA-Z0-9_.:/=,@+\-]+$/
  if (![executable, ...args].every(value => safe.test(value))) {
    throw new Error('Windows batch commands accept only fixed CLI arguments; use a native executable for paths or shell text.')
  }
  const line = `"${executable}" ${args.map(value => `"${value}"`).join(' ')}`
  return spawn(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', `"${line}"`], {
    ...options, shell: false, windowsVerbatimArguments: true,
  })
}

/** Frontend tooling only receives public configuration, never the backend's local credentials. */
export function frontendEnvironment(env) {
  return Object.fromEntries(Object.entries(env).filter(([key]) =>
    !/^(DB_|MYSQL_|JWT_|CAMPUS_(?:ADMIN|USER|BOOTSTRAP|DEMO)_|TEST_DB_|SPRING_|UPLOAD_DIR$)/i.test(key)))
}
