const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { ref, reactive, computed } = require('vue')
const source = fs.readFileSync(path.join(__dirname, '../src/pages/demands/demands.vue'), 'utf8')
const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const setup = new Function('ref', 'reactive', 'computed', 'onShow', 'onUnload', 'http', 'uni', 'TOKEN_KEY', 'showAppModal', `${script}
return { loadCategories, applyDemand, saveDemand, categories, categoriesError, selectedCategory, form, formError };`)
const available = [{id:1,name:'Books'},{id:2,name:'Tools'}]
const demand = {id:11,version:3,categoryId:1,description:'Keep my description',preferredTags:[],offeredItems:[]}
function harness() {
  const reads = [], writes = []
  const http = {
    get: url => new Promise((resolve,reject) => reads.push({url,resolve,reject})),
    patch: (url,body) => new Promise((resolve,reject) => writes.push({url,body,resolve,reject})),
    post: (url,body) => new Promise((resolve,reject) => writes.push({url,body,resolve,reject})),
  }
  const app = setup(ref, reactive, computed, () => {}, () => {}, http, {getStorageSync:()=>'ui-fixture'}, 'token', () => {})
  app.categories.value = available
  app.applyDemand(demand)
  return {app,reads,writes}
}

test('category reorder retains the selected ID and never silently selects another category', async () => {
  const h = harness()
  const loading = h.app.loadCategories()
  h.reads[0].resolve([...available].reverse())
  await loading
  assert.equal(h.app.form.categoryIndex, 1)
  assert.equal(h.app.selectedCategory.value.id, 1)
  assert.equal(h.app.form.version, 3)
  assert.equal(h.app.form.description, demand.description)
})

for (const next of [[available[1]], []]) {
  test(`disabled category selection is cleared with ${next.length} active alternatives and save is blocked`, async () => {
    const h = harness()
    const loading = h.app.loadCategories()
    h.reads[0].resolve(next)
    await loading
    assert.equal(h.app.form.categoryIndex, -1)
    assert.equal(h.app.selectedCategory.value, null)
    assert.equal(h.app.form.description, demand.description)
    await h.app.saveDemand()
    assert.equal(h.writes.length, 0)
    assert.equal(h.app.formError.value, '请选择想要的分类')
  })
}

test('out-of-order category responses cannot replace the latest selection or report stale errors', async () => {
  for (const outcome of ['success','failure']) {
    const h = harness()
    const first = h.app.loadCategories(), second = h.app.loadCategories()
    h.reads[1].resolve([...available].reverse())
    await second
    if (outcome === 'success') h.reads[0].resolve([])
    else h.reads[0].reject(new Error('Old error'))
    await first
    assert.equal(h.app.selectedCategory.value.id, 1)
    assert.equal(h.app.form.categoryIndex, 1)
    assert.equal(h.app.categoriesError.value, '')
  }
})

test('a server 400 refreshes available categories without losing input or advancing the demand version', async () => {
  const h = harness()
  h.app.form.description = 'Unsaved input'
  const saving = h.app.saveDemand()
  assert.equal(h.writes[0].body.categoryId, 1)
  h.writes[0].reject(Object.assign(new Error('分类已停用，请选择可用分类'), {status:400}))
  await Promise.resolve()
  assert.equal(h.reads[0].url, '/api/categories')
  h.reads[0].resolve([available[1]])
  await saving
  assert.equal(h.app.selectedCategory.value, null)
  assert.equal(h.app.form.description, 'Unsaved input')
  assert.equal(h.app.form.version, 3)
  assert.match(h.app.formError.value, /分类已停用/)
  assert.equal(h.writes.length, 1)
})
