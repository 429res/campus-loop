// Compile only pure matching classes and test fixtures into a disposable directory; no DB/env loading.
import {execFileSync} from 'node:child_process'
import {mkdtempSync,rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {resolve,dirname} from 'node:path'
import {fileURLToPath} from 'node:url'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
const env={...process.env}
if(process.platform==='darwin'&&!env.JAVA_HOME)env.JAVA_HOME=execFileSync('/usr/libexec/java_home',['-v','17'],{encoding:'utf8'}).trim()
const bin=name=>env.JAVA_HOME?resolve(env.JAVA_HOME,'bin',process.platform==='win32'?name+'.exe':name):name
const output=mkdtempSync(resolve(tmpdir(),'campus-matching-benchmark-'))
const main='sys-project/sys-project-com/src/main/java/edu/campusloop/matching/'
const test='sys-project/sys-project-com/src/test/java/edu/campusloop/matching/'
try {
  execFileSync(bin('javac'),['--release','17','-d',output,...[main+'IndependentMatchingInput.java',main+'IndependentDemandMatcher.java',test+'ReferenceIndependentDemandMatcher.java',test+'MatchingBenchmarkData.java',test+'MatchingBenchmark.java'].map(p=>resolve(root,p))],{env,stdio:'inherit'})
  for(let fork=1;fork<=3;fork++)execFileSync(bin('java'),['-Xms256m','-Xmx256m','-XX:+UseSerialGC','-cp',output,'edu.campusloop.matching.MatchingBenchmark',String(fork)],{env,stdio:'inherit'})
} finally {rmSync(output,{recursive:true,force:true})}
