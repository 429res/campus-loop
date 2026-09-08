import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLatestRequestGuard } from '../src/common/latest-request.mjs'

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
