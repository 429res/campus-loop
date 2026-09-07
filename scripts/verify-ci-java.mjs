import { spawnSync } from 'node:child_process'
import { join } from 'node:path'

// setup-java consumes Adoptium's SemVer alias, not Java's four-part runtime version.
// Both official Linux/Windows jdk-17.0.20.1+1 metadata files declare semver 17.0.20+101.
const executable = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  : 'java'
const result = spawnSync(executable, ['-XshowSettings:properties', '-version'], { encoding: 'utf8', shell: false })
if (result.error || result.status !== 0) throw new Error('Cannot execute the configured CI JDK.')
const output = `${result.stdout}\n${result.stderr}`
const runtime = output.match(/^\s*java\.runtime\.version\s*=\s*(\S+)/m)?.[1]
const vendor = output.match(/^\s*java\.vendor\s*=\s*(.+)$/m)?.[1]?.trim()
if (runtime !== '17.0.20.1+1' || vendor !== 'Eclipse Adoptium') {
  throw new Error(`CI requires Eclipse Adoptium 17.0.20.1+1; found ${vendor || 'unknown vendor'} ${runtime || 'unknown version'}.`)
}
console.log(`Verified CI JDK: ${vendor} ${runtime}`)
