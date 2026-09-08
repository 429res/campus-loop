import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLatestRequestGuard } from '../src/common/latest-request.mjs'
import { readFileSync } from 'node:fs'
import { computed, ref } from 'vue'

const TOKEN_KEY = 'campus-loop-token'
function pageHarness(page) {
  const source = readFileSync(new URL(`../src/pages/${page}/${page}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const storage = new Map(), requests = [], lifecycle = {}, navigations = []
  const uni = {
    getStorageSync: key => storage.get(key),
    navigateTo: options => navigations.push(options),
    switchTab: options => navigations.push(options),
  }
  const http = { get: (url, data) => {
    let resolve, reject
    const promise = new Promise((yes, no) => { resolve = yes; reject = no })
    const request = { url, data, resolve, reject, aborted: false }
    promise.abort = () => { request.aborted = true }
    requests.push(request)
    return promise
  } }
  const exports = page === 'matches'
    ? 'load, authenticated, loading, recommendations, preview, previewMatch, authNotice, error, errorStatus, login'
    : 'authenticated, login, recommendations'
  const setup = new Function('ref', 'computed', 'onShow', 'onUnload', 'uni', 'http', 'TOKEN_KEY', 'isAbortError', 'createLatestRequestGuard', `${script}\nreturn {${exports}};`)
  const app = setup(ref, computed, cb => { lifecycle.show = cb }, cb => { lifecycle.unload = cb }, uni, http, TOKEN_KEY, error => error?.code === 'ABORTED', createLatestRequestGuard)
  return { app, storage, requests, lifecycle, navigations }
}

test('current recommendation 401 restores login after the HTTP wrapper clears storage', async () => {
  const h = pageHarness('matches')
  h.storage.set(TOKEN_KEY, 'session-a')
  const loading = h.lifecycle.show()
  h.storage.delete(TOKEN_KEY)
  h.requests[0].reject({ status: 401, message: '登录已过期，请重新登录' })
  await loading
  assert.equal(h.app.authenticated.value, false)
  assert.equal(h.app.loading.value, false)
  assert.match(h.app.authNotice.value, /登录已过期/)
  assert.deepEqual(h.app.recommendations.value, [])
  h.app.login()
  assert.deepEqual(h.navigations, [{ url: '/pages/login/login?redirect=matches&reason=session-expired' }])
})

test('late 401 cannot replace another account recommendation state', async () => {
  const h = pageHarness('matches')
  h.storage.set(TOKEN_KEY, 'session-a')
  const first = h.app.load()
  h.storage.set(TOKEN_KEY, 'session-b')
  const second = h.app.load()
  h.requests[1].resolve({ ruleVersion: 'independent-v2', recommendations: [{ id: 'b', length: 2 }] })
  await second
  h.requests[0].reject({ status: 401, message: 'old session expired' })
  await first
  assert.equal(h.app.authenticated.value, true)
  assert.deepEqual(h.app.recommendations.value, [{ id: 'b', length: 2 }])
  assert.equal(h.app.authNotice.value, '')
  assert.equal(h.app.loading.value, false)
})

test('401 from an invalidated request cannot restore expired-session state', async () => {
  const h = pageHarness('matches')
  h.storage.set(TOKEN_KEY, 'session-a')
  const first = h.app.load()
  h.storage.delete(TOKEN_KEY)
  await h.app.load()
  h.requests[0].reject({ status: 401, message: 'old session expired' })
  await first
  assert.equal(h.app.authNotice.value, '')
  assert.equal(h.app.loading.value, false)
})

test('recommendation failure clears previous results and keeps a scale-limit error distinct', async () => {
  const h = pageHarness('matches')
  h.storage.set(TOKEN_KEY, 'session-a')
  const first = h.app.load()
  h.requests[0].resolve({ ruleVersion: 'independent-v2', recommendations: [{ id: 'previous', length: 2 }] })
  await first
  h.app.previewMatch(h.app.recommendations.value[0])
  const second = h.app.load()
  assert.deepEqual(h.app.recommendations.value, [])
  assert.equal(h.app.preview.value, null)
  h.requests[1].reject({ status: 422, message: '候选规模超限' })
  await second
  assert.equal(h.app.errorStatus.value, 422)
  assert.equal(h.app.error.value, '候选规模超限')
  assert.equal(h.app.loading.value, false)
})

test('exchange page refreshes authentication on login return and logout', () => {
  const h = pageHarness('exchanges')
  h.lifecycle.show()
  assert.equal(h.app.authenticated.value, false)
  h.storage.set(TOKEN_KEY, 'session-a')
  h.lifecycle.show()
  assert.equal(h.app.authenticated.value, true)
  h.storage.delete(TOKEN_KEY)
  h.lifecycle.show()
  assert.equal(h.app.authenticated.value, false)
  h.app.login()
  assert.deepEqual(h.navigations, [{ url: '/pages/login/login?redirect=exchanges' }])
  assert.equal(h.requests.length, 0)
})

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

test('exchange preview remains read-only until its page integration is delivered', () => {
  const source = readFileSync(new URL('../src/pages/matches/matches.vue',import.meta.url),'utf8')
  assert.match(source,/发起前预览/)
  assert.match(source,/正式发起 · 待开放/)
  assert.doesNotMatch(source,/尚未提供正式 DTO|待后端依赖/)
  assert.doesNotMatch(source,/http\.(post|put|patch|delete)\(['"`]\/api\/exchanges/)
})

test('my exchanges reads persisted records and limits this slice to the server-authorized dispute action', () => {
  const source = readFileSync(new URL('../src/pages/exchanges/exchanges.vue',import.meta.url),'utf8')
  assert.match(source,/http\.get\('\/api\/exchanges\/mine'/)
  assert.match(source,/allowedActions\?\.includes\('DISPUTE'\)/)
  assert.match(source,/\/dispute`/)
  assert.doesNotMatch(source,/\/confirm`|\/cancel`|\/handoff`/)
})
