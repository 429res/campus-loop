// Inspect tracked deliverables, not ignored local credentials or the reference project.
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { root } from './env.mjs'
const files=execFileSync('git',['ls-files','-z'],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean)
const forbidden=files.filter(file=>/(^|\/)(node_modules|target|dist|unpackage|uploads|logs|\.local)(\/|$)/.test(file)||(/(^|\/)\.env(\.|$)/.test(file)&&!file.endsWith('.env.example'))||file.endsWith('project.private.config.json'))
if(forbidden.length)throw new Error('Tracked generated/private files: '+forbidden.join(', '))
const required=['README.md','CONTRIBUTING.md','AGENTS.md','compose.yaml','project-self/package-lock.json','wx-project-self/package-lock.json','sys-project/mvnw','sys-project/mvnw.cmd','sys-project/.mvn/wrapper/maven-wrapper.properties','shared/design-tokens.css','docs/architecture.md','docs/api-contract.md','docs/design-system.md','docs/roadmap.md']
for(const file of required)if(!files.includes(file))throw new Error('Missing tracked deliverable: '+file)
for(const dir of ['project-self','wx-project-self']){
 const pkg=JSON.parse(readFileSync(`${root}/${dir}/package.json`,'utf8'));const lock=JSON.parse(readFileSync(`${root}/${dir}/package-lock.json`,'utf8'))
 if(pkg.name!==lock.name)throw new Error('Package lock name mismatch: '+dir)
 for(const key of ['dependencies','devDependencies'])if(JSON.stringify(pkg[key])!==JSON.stringify(lock.packages[''][key]))throw new Error('Lock dependency mismatch: '+dir)
}
console.log(`Repository deliverables verified: ${files.length} tracked files, no tracked local env/dependencies/builds/uploads.`)
