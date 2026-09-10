const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { ref, watch, effectScope } = require('vue')

// Run the real page setup with Vue reactivity and controllable UniApp I/O.
// Requests deliberately remain settleable after abort to exercise late callbacks.
const source = fs.readFileSync(path.join(__dirname, '../src/pages/publish/publish.vue'), 'utf8')
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const setup = new Function('ref', 'watch', 'onShow', 'onUnload', 'http', 'uni', 'TOKEN_KEY', 'USER_KEY', 'imageUrl', 'isAbortError', `${script}
return { submitted, uploadProgress, verifySession, publish, pickImage, startUpload, cancelUpload, removeImage, form, currentUser, loggedIn, verifyingSession, busy, uploading, pendingFile, uploadError, error, sessionError, draftNotice, categories, categoriesError };`)
const TOKEN_KEY = 'campus-loop-token'
const USER_KEY = 'campus-loop-user'
const draftKey = id => `campus-loop-publish-draft-${id}`
const draft = (title, imageUrl = '') => ({ title, description: 'A saved description', categoryId: 1, conditionLevel: 3, tags: '', wantedCategoryId: 1, wantedTags: '', imageUrl })
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  const request = { promise, resolve, reject, aborted: false }
  promise.abort = () => { request.aborted = true }
  return request
}
function harness(t, { manualCategories = false } = {}) {
  const storage = new Map(), requests = [], posts = [], uploads = [], choices = [], navigations = []
  const lifecycle = {}
  const uni = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
    removeStorageSync: key => storage.delete(key),
    navigateTo: options => navigations.push(options),
    chooseImage: options => choices.push(options),
    showToast: () => {},
  }
  function enqueue(list, data) {
    const request = { ...deferred(), token: storage.get(TOKEN_KEY), ...data }
    // Track abort on the same object that assertions inspect.
    request.promise.abort = () => { request.aborted = true }
    list.push(request)
    return request.promise
  }
  const http = {
    get: url => url === '/api/categories' && !manualCategories ? Promise.resolve([{ id: 1, name: 'Books' }]) : enqueue(requests, { url }),
    post: (url, data) => enqueue(posts, { url, data: structuredClone(data) }),
    upload: filePath => enqueue(uploads, { filePath }),
  }
  const scope = effectScope()
  const app = scope.run(() => setup(ref, watch, cb => { lifecycle.show = cb }, cb => { lifecycle.unload = cb }, http, uni, TOKEN_KEY, USER_KEY, value => value, e => e?.code === 'ABORTED'))
  t.after(() => scope.stop())
  async function authenticate(id, token = `test-session-${id}`) {
    storage.set(TOKEN_KEY, token)
    const pending = app.verifySession()
    requests.at(-1).resolve({ id, displayName: `Account ${id}` })
    await pending
  }
  return { app, storage, requests, posts, uploads, choices, navigations, lifecycle, authenticate }
}

test('account switch hides and blocks the old form until verification, then publishes only the new draft', async t => {
  const h = harness(t)
  h.storage.set(draftKey(1), { form: draft('Account A draft') })
  h.storage.set(draftKey(2), { form: draft('Account B draft') })
  await h.authenticate(1)
  h.app.form.value.description = 'Saved immediately before leaving'
  h.storage.set(TOKEN_KEY, 'test-session-2')
  const switching = h.lifecycle.show()
  assert.equal(h.app.loggedIn.value, false)
  assert.equal(h.app.verifyingSession.value, true)
  assert.equal(h.app.currentUser.value, null)
  assert.equal(h.app.form.value.title, '')
  await h.app.publish()
  h.app.pickImage()
  assert.equal(h.posts.length, 0)
  assert.equal(h.choices.length, 0)
  h.requests.at(-1).resolve({ id: 2 })
  await switching
  assert.equal(h.app.form.value.title, 'Account B draft')
  const publishing = h.app.publish()
  assert.equal(h.posts[0].token, 'test-session-2')
  assert.equal(h.posts[0].data.title, 'Account B draft')
  h.posts[0].resolve({ id: 22 })
  await publishing
  assert.equal(h.storage.has(draftKey(2)), false)
  assert.equal(h.storage.get(draftKey(1)).form.description, 'Saved immediately before leaving')
  assert.deepEqual(h.navigations, [])
  assert.equal(h.app.submitted.value.id,22)
})

test('out-of-order /me responses cannot restore an old account or overwrite the user cache', async t => {
  const h = harness(t)
  h.storage.set(draftKey(1), { form: draft('A') })
  h.storage.set(draftKey(2), { form: draft('B') })
  h.storage.set(TOKEN_KEY, 'test-session-1')
  const first = h.app.verifySession()
  h.storage.set(TOKEN_KEY, 'test-session-2')
  const second = h.app.verifySession()
  h.requests[1].resolve({ id: 2 })
  await second
  h.requests[0].resolve({ id: 1 })
  await first
  assert.equal(h.app.currentUser.value.id, 2)
  assert.equal(h.app.form.value.title, 'B')
  assert.equal(h.storage.get(USER_KEY).id, 2)
  assert.equal(h.app.loggedIn.value, true)
})

test('a changed token rejects a pending /me response even before another verification starts', async t => {
  const h = harness(t)
  h.storage.set(draftKey(1), { form: draft('Account A') })
  h.storage.set(TOKEN_KEY, 'test-session-1')
  const pending = h.app.verifySession()
  h.storage.set(TOKEN_KEY, 'test-session-2')
  h.storage.set(USER_KEY, { id: 2 })
  h.requests[0].resolve({ id: 1 })
  await pending
  assert.equal(h.app.loggedIn.value, false)
  assert.equal(h.app.currentUser.value, null)
  assert.equal(h.app.form.value.title, '')
  assert.equal(h.storage.get(USER_KEY).id, 2)
})

test('verification generation also rejects an older failure for the same token', async t => {
  const h = harness(t)
  await h.authenticate(1)
  h.app.form.value.title = 'Keep this draft'
  const first = h.app.verifySession()
  const second = h.app.verifySession()
  h.requests[2].resolve({ id: 1 })
  await second
  h.requests[1].reject(new Error('An old network failure'))
  await first
  assert.equal(h.app.loggedIn.value, true)
  assert.equal(h.app.form.value.title, 'Keep this draft')
  assert.equal(h.app.sessionError.value, '')
})

test('actions reject a changed token even before the page receives onShow', async t => {
  const h = harness(t)
  await h.authenticate(1)
  h.app.form.value = draft('Account A')
  h.storage.set(TOKEN_KEY, 'test-session-2')
  await h.app.publish()
  assert.equal(h.posts.length, 0)
  assert.equal(h.app.loggedIn.value, false)
  assert.equal(h.app.form.value.title, '')
  assert.equal(h.storage.get(draftKey(1)).form.title, 'Account A')
})

test('logout clears transient state, aborts uploads, and preserves the same account draft for a new session', async t => {
  const h = harness(t)
  await h.authenticate(1)
  h.app.form.value = draft('Keep my text')
  h.app.pendingFile.value = '/temporary/a.jpg'
  const upload = h.app.startUpload(h.app.pendingFile.value)
  h.storage.delete(TOKEN_KEY)
  await h.lifecycle.show()
  assert.equal(h.uploads[0].aborted, true)
  assert.equal(h.app.uploading.value, false)
  assert.equal(h.app.form.value.title, '')
  assert.equal(h.app.pendingFile.value, '')
  h.uploads[0].resolve({ url: '/uploads/old-a.jpg' })
  await upload
  await h.authenticate(1, 'replacement-session-1')
  assert.equal(h.app.form.value.title, 'Keep my text')
  assert.equal(h.app.form.value.imageUrl, '')
  assert.equal(h.app.pendingFile.value, '')
  assert.match(h.app.draftNotice.value, /已恢复/)
})

for (const outcome of ['success', 'unauthorized']) {
  test(`late upload ${outcome} cannot affect a replacement account or its active upload`, async t => {
    const h = harness(t)
    await h.authenticate(1)
    h.app.form.value = draft('A')
    const uploadA = h.app.startUpload('/temporary/a.jpg')
    h.storage.set(draftKey(2), { form: draft('B', '/uploads/b-existing.jpg') })
    await h.authenticate(2)
    assert.equal(h.uploads[0].aborted, true)
    const uploadB = h.app.startUpload('/temporary/b.jpg')
    if (outcome === 'success') h.uploads[0].resolve({ url: '/uploads/old-a.jpg' })
    else h.uploads[0].reject(Object.assign(new Error('Old session expired'), { status: 401 }))
    await uploadA
    assert.equal(h.app.currentUser.value.id, 2)
    assert.equal(h.app.loggedIn.value, true)
    assert.equal(h.app.form.value.imageUrl, '/uploads/b-existing.jpg')
    assert.equal(h.app.uploading.value, true)
    assert.equal(h.app.uploadError.value, '')
    h.uploads[1].resolve({ url: '/uploads/b-new.jpg' })
    await uploadB
    assert.equal(h.app.form.value.imageUrl, '/uploads/b-new.jpg')
    assert.equal(h.storage.get(draftKey(2)).form.imageUrl, '/uploads/b-new.jpg')
    assert.equal(h.app.uploading.value, false)
  })
  test(`late publish ${outcome} cannot delete a replacement draft, navigate, or finish its active request`, async t => {
    const h = harness(t)
    await h.authenticate(1)
    h.app.form.value = draft('A')
    const publishA = h.app.publish()
    h.storage.set(draftKey(2), { form: draft('B') })
    await h.authenticate(2)
    const publishB = h.app.publish()
    if (outcome === 'success') h.posts[0].resolve({ id: 11 })
    else h.posts[0].reject(Object.assign(new Error('Old session expired'), { status: 401 }))
    await publishA
    assert.equal(h.app.currentUser.value.id, 2)
    assert.equal(h.app.form.value.title, 'B')
    assert.equal(h.storage.get(draftKey(2)).form.title, 'B')
    assert.equal(h.storage.get(draftKey(1)).form.title, 'A')
    assert.equal(h.app.busy.value, true)
    assert.equal(h.app.error.value, '')
    assert.equal(h.navigations.length, 0)
    h.posts[1].resolve({ id: 22 })
    await publishB
    assert.equal(h.app.busy.value, false)
    assert.equal(h.storage.has(draftKey(2)), false)
    assert.deepEqual(h.navigations, [])
  assert.equal(h.app.submitted.value.id,22)
  })
}

test('an expired publish session keeps its draft and supports normal publication after re-login', async t => {
  const h = harness(t)
  await h.authenticate(1)
  h.app.form.value = draft('After re-login')
  const expired = h.app.publish()
  h.storage.delete(TOKEN_KEY) // The shared HTTP wrapper clears the expired token.
  h.posts[0].reject(Object.assign(new Error('Session expired'), { status: 401 }))
  await expired
  assert.equal(h.app.loggedIn.value, false)
  assert.equal(h.app.form.value.title, '')
  assert.equal(h.app.error.value, 'Session expired')
  await h.authenticate(1, 'replacement-session-1')
  assert.equal(h.app.form.value.title, 'After re-login')
  const publishing = h.app.publish()
  assert.equal(h.posts[1].token, 'replacement-session-1')
  h.posts[1].resolve({ id: 33 })
  await publishing
  assert.equal(h.storage.has(draftKey(1)), false)
})

test('a native image picker callback from an old account cannot start an upload', async t => {
  const h = harness(t)
  await h.authenticate(1)
  h.app.pickImage()
  await h.authenticate(2)
  h.choices[0].success({ tempFilePaths: ['/temporary/old-account.jpg'] })
  assert.equal(h.uploads.length, 0)
  assert.equal(h.app.pendingFile.value, '')
  assert.equal(h.app.form.value.imageUrl, '')
})

test('same-session onShow verification preserves an in-flight native picker and its upload', async t => {
  const h = harness(t)
  await h.authenticate(1)
  h.app.form.value = draft('Same account')
  h.app.pickImage()
  const showing = h.lifecycle.show()
  assert.equal(h.app.verifyingSession.value, true)
  h.choices[0].success({ tempFilePaths: ['/temporary/current.jpg'] })
  assert.equal(h.uploads.length, 1)
  h.uploads[0].resolve({ url: '/uploads/current.jpg' })
  await h.uploads[0].promise
  h.requests.at(-1).resolve({ id: 1 })
  await showing
  assert.equal(h.app.form.value.title, 'Same account')
  assert.equal(h.app.form.value.imageUrl, '/uploads/current.jpg')
  assert.equal(h.app.uploading.value, false)
})

test('a canceled upload cannot overwrite its retry even when it completes after abort', async t => {
  const h = harness(t)
  await h.authenticate(1)
  const first = h.app.startUpload('/temporary/first.jpg')
  h.app.cancelUpload()
  assert.equal(h.uploads[0].aborted, true)
  const second = h.app.startUpload('/temporary/retry.jpg')
  h.uploads[0].resolve({ url: '/uploads/obsolete.jpg' })
  await first
  assert.equal(h.app.uploading.value, true)
  assert.equal(h.app.form.value.imageUrl, '')
  h.uploads[1].resolve({ url: '/uploads/retry.jpg' })
  await second
  assert.equal(h.app.form.value.imageUrl, '/uploads/retry.jpg')
})

test('unload invalidates pending verification so it cannot restore the disposed page', async t => {
  const h = harness(t)
  h.storage.set(TOKEN_KEY, 'test-session-1')
  const pending = h.app.verifySession()
  h.lifecycle.unload()
  h.requests[0].resolve({ id: 1 })
  await pending
  assert.equal(h.app.loggedIn.value, false)
  assert.equal(h.app.currentUser.value, null)
  assert.equal(h.app.verifyingSession.value, false)
  assert.equal(h.storage.has(USER_KEY), false)
})

for (const outcome of ['success', 'failure']) {
  test(`late categories ${outcome} from an old onShow cannot overwrite the new list or draft`, async t => {
    const h = harness(t, { manualCategories: true })
    h.storage.set(TOKEN_KEY, 'test-session-1')
    const showA = h.lifecycle.show()
    const categoriesA = h.requests.findLast(r => r.url === '/api/categories')
    const meA = h.requests.findLast(r => r.url === '/api/auth/me')
    meA.resolve({ id: 1 })
    h.storage.set(TOKEN_KEY, 'test-session-2')
    h.storage.set(draftKey(2), { form: { ...draft('B'), categoryId: 2, wantedCategoryId: 2 } })
    const showB = h.lifecycle.show()
    h.requests.findLast(r => r.url === '/api/categories').resolve([{ id: 1, name: 'Books' }, { id: 2, name: 'New category' }])
    h.requests.findLast(r => r.url === '/api/auth/me').resolve({ id: 2 })
    await showB
    if (outcome === 'success') categoriesA.resolve([{ id: 1, name: 'Old snapshot' }])
    else categoriesA.reject(new Error('Old category request failed'))
    await showA
    assert.equal(h.app.currentUser.value.id, 2)
    assert.equal(h.app.categories.value.length, 2)
    assert.equal(h.app.categoriesError.value, '')
    assert.equal(h.app.form.value.categoryId, 2)
    assert.equal(h.app.form.value.wantedCategoryId, 2)
    assert.equal(h.storage.get(draftKey(2)).form.categoryId, 2)
  })
}

test('an old onShow completion cannot validate the new draft against categories still being refreshed', async t => {
  const h = harness(t, { manualCategories: true })
  h.storage.set(TOKEN_KEY, 'test-session-1')
  const showA = h.lifecycle.show()
  h.requests.findLast(r => r.url === '/api/categories').resolve([{ id: 1, name: 'Old snapshot' }])
  const meA = h.requests.findLast(r => r.url === '/api/auth/me')
  // Let the old list load before switching, while its /me remains in flight.
  await Promise.resolve()
  h.storage.set(TOKEN_KEY, 'test-session-2')
  h.storage.set(draftKey(2), { form: { ...draft('B'), categoryId: 2, wantedCategoryId: 2 } })
  const showB = h.lifecycle.show()
  const categoriesB = h.requests.findLast(r => r.url === '/api/categories')
  h.requests.findLast(r => r.url === '/api/auth/me').resolve({ id: 2 })
  await Promise.resolve()
  meA.resolve({ id: 1 })
  await showA
  assert.equal(h.app.form.value.categoryId, 2)
  assert.equal(h.storage.get(draftKey(2)).form.categoryId, 2)
  categoriesB.resolve([{ id: 1, name: 'Books' }, { id: 2, name: 'New category' }])
  await showB
  assert.equal(h.app.form.value.categoryId, 2)
})

test('a failed refresh keeps the restored draft instead of validating it against the previous list', async t => {
  const h = harness(t, { manualCategories: true })
  h.storage.set(TOKEN_KEY, 'test-session-1')
  const showA = h.lifecycle.show()
  h.requests.findLast(r => r.url === '/api/categories').resolve([{ id: 1, name: 'Old snapshot' }])
  h.requests.findLast(r => r.url === '/api/auth/me').resolve({ id: 1 })
  await showA
  h.storage.set(TOKEN_KEY, 'test-session-2')
  h.storage.set(draftKey(2), { form: { ...draft('B'), categoryId: 2, wantedCategoryId: 2 } })
  const showB = h.lifecycle.show()
  h.requests.findLast(r => r.url === '/api/auth/me').resolve({ id: 2 })
  h.requests.findLast(r => r.url === '/api/categories').reject(new Error('Retry needed'))
  await showB
  assert.equal(h.app.categoriesError.value, 'Retry needed')
  assert.equal(h.app.form.value.categoryId, 2)
  assert.equal(h.storage.get(draftKey(2)).form.categoryId, 2)
})

for (const outcome of ['success', 'failure']) {
  test(`unload invalidates a pending categories ${outcome}`, async t => {
    const h = harness(t, { manualCategories: true })
    h.storage.set(TOKEN_KEY, 'test-session-1')
    const showing = h.lifecycle.show()
    h.requests.findLast(r => r.url === '/api/auth/me').resolve({ id: 1 })
    const categories = h.requests.findLast(r => r.url === '/api/categories')
    h.lifecycle.unload()
    if (outcome === 'success') categories.resolve([{ id: 1, name: 'Stale result' }])
    else categories.reject(new Error('Disposed request failed'))
    await showing
    assert.equal(h.app.categories.value.length, 0)
    assert.equal(h.app.categoriesError.value, '')
    assert.equal(h.app.loggedIn.value, false)
  })
}

test('an empty active category catalog clears both restored selections and blocks stale publication', async t => {
  const h = harness(t, { manualCategories: true })
  h.storage.set(TOKEN_KEY, 'test-session-1')
  h.storage.set(draftKey(1), { form: draft('Keep my text') })
  const showing = h.lifecycle.show()
  h.requests.findLast(r => r.url === '/api/auth/me').resolve({ id: 1 })
  h.requests.findLast(r => r.url === '/api/categories').resolve([])
  await showing
  assert.equal(h.app.form.value.title, 'Keep my text')
  assert.equal(h.app.form.value.categoryId, '')
  assert.equal(h.app.form.value.wantedCategoryId, '')
  assert.equal(h.storage.get(draftKey(1)).form.categoryId, '')
  assert.match(h.app.draftNotice.value, /分类已清除/)
  await h.app.publish()
  assert.equal(h.posts.length, 0)
})
