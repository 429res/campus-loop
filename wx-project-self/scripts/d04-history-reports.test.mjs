import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const read = path => readFileSync(new URL(`../${path}`,import.meta.url),'utf8')
const history = read('src/pages/history/history.vue')
const exchanges = read('src/pages/exchanges/exchanges.vue')
const governance = read('src/pages/governance/governance.vue')
const detail = read('src/pages/detail/detail.vue')
const http = read('src/common/http.js')
const pages = read('src/pages.json')

test('D-04 registers real history reads, self reports, evidence, and confirmation actions', () => {
  assert.match(history,/GET|http\.get\(`\/api\/items\/\$\{itemId\}\/history`/)
  assert.match(history,/http\.post\(`\/api\/items\/\$\{itemId\}\/history`/)
  assert.match(http,/uploadEvidence:.*\/api\/uploads\/evidence/)
  for (const action of ['confirmation-request','confirm','withdraw-confirmation']) assert.match(history,new RegExp(action))
  for (const source of ['SELF_REPORTED','BOTH_CONFIRMED','ADMIN_VERIFIED']) assert.match(history,new RegExp(source))
  assert.match(history,/结果无法确认/)
})

test('participant dispute uses persisted version and server allowedActions', () => {
  assert.match(exchanges,/\/api\/exchanges\/mine/)
  assert.match(exchanges,/allowedActions\.includes\(action\)/)
  assert.match(read('src/common/exchange-workflow.mjs'),/DISPUTE/)
  assert.match(exchanges,/version:detail\.value\.version/)
  assert.match(exchanges,/uncertainOnFailure:true/)
})

test('reports use real private routes and retain the idempotent request before submission', () => {
  assert.match(governance,/http\.post\('\/api\/reports',pending\.value/)
  assert.match(governance,/http\.get\('\/api\/reports\/mine'/)
  assert.match(governance,/uni\.setStorageSync\(storageKey,payload\)/)
  assert.match(governance,/uncertainOnFailure:true/)
})

test('detail and route manifest expose the D-04 entry points', () => {
  assert.match(detail,/pages\/history\/history/)
  assert.match(detail,/pages\/governance\/governance/)
  assert.match(pages,/pages\/history\/history/)
  assert.match(pages,/pages\/governance\/governance/)
})
