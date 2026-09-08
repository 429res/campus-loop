import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLatestRequestGuard } from '../src/common/latest-request.mjs'
import { readFileSync } from 'node:fs'

test('a newer recommendation request rejects an older response', () => {
  let token = 'session-a'
  const guard = createLatestRequestGuard(() => token)
  const first = guard.begin()
  const second = guard.begin()
  assert.equal(guard.isCurrent(first), false)
  assert.equal(guard.isCurrent(second), true)
})

test('an account change rejects a response even without another request', () => {
  let token = 'session-a'
  const guard = createLatestRequestGuard(() => token)
  const pending = guard.begin()
  token = 'session-b'
  assert.equal(guard.isCurrent(pending), false)
})

test('unload invalidates the pending response', () => {
  const guard = createLatestRequestGuard(() => 'session-a')
  const pending = guard.begin()
  guard.invalidate()
  assert.equal(guard.isCurrent(pending), false)
})

test('exchange preview remains read-only while B-03 and A-03 are unavailable', () => {
  const source = readFileSync(new URL('../src/pages/matches/matches.vue',import.meta.url),'utf8')
  assert.match(source,/发起前预览/)
  assert.match(source,/正式发起 · 待 B-03\/A-03/)
  assert.doesNotMatch(source,/http\.(post|put|patch|delete)\(['"`]\/api\/exchanges/)
})

test('my exchanges placeholder never fabricates server records or actions', () => {
  const source = readFileSync(new URL('../src/pages/exchanges/exchanges.vue',import.meta.url),'utf8')
  assert.match(source,/交换写入口仍返回 501/)
  assert.match(source,/allowedActions/)
  assert.doesNotMatch(source,/http\.(get|post|put|patch|delete)\(/)
})
