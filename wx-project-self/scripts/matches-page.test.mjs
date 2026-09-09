import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createLatestRequestGuard } from '../src/common/latest-request.mjs'
import { readFileSync } from 'node:fs'
import { computed, ref } from 'vue'
import { EXCHANGE_STATUSES, STATUS_LABELS, ACTION_LABELS, createExchangeJournal, creationSnapshot, actionRequest, expiryText } from '../src/common/exchange-workflow.mjs'

const TOKEN_KEY = 'campus-loop-token', USER_KEY = 'campus-loop-user'
function pageHarness(page,options={}) {
  const source = readFileSync(new URL(`../src/pages/${page}/${page}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const storage = options.storage || new Map(), requests = [], lifecycle = {}, navigations = []
  const uni = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key,value) => storage.set(key,value),
    navigateTo: options => navigations.push(options),
    switchTab: options => navigations.push(options),
  }
  const send = (method,url, data) => {
    if(url==='/api/matches/readiness') return Promise.resolve({AVAILABLE:1,DEMANDS:1})
    if(url.startsWith('/api/items/')) return Promise.resolve({title:'真实服务器物品标题'})
    let resolve, reject
    const promise = new Promise((yes, no) => { resolve = yes; reject = no })
    const request = { method, url, data, resolve, reject, aborted: false }
    promise.abort = () => { request.aborted = true }
    requests.push(request)
    return promise
  }
  const http={get:(url,data)=>send('GET',url,data),post:(url,data)=>send('POST',url,data)}
  const exports = page === 'matches'
    ? 'load, authenticated, loading, recommendations, preview, previewMatch, authNotice, error, errorStatus, login, creation, createBusy, createError, createExchange, reviewRetained'
    : 'authenticated, login, recommendations, loadList, loadDetail, detail, pending, reason, acknowledged, beginAction, submitAction, useCurrentVersion, actionError, actionBusy, targetId, records, page, filter, total, refreshActionDetail, dismissDraft, restoreEditor'
  const dependencies={ref,computed,onLoad:cb=>{lifecycle.load=cb},onShow:cb=>{lifecycle.show=cb},onHide:cb=>{lifecycle.hide=cb},onUnload:cb=>{lifecycle.unload=cb},uni,http,TOKEN_KEY,USER_KEY,isAbortError:error=>error?.code==='ABORTED',createLatestRequestGuard,createExchangeJournal,creationSnapshot,actionRequest,expiryText,EXCHANGE_STATUSES,STATUS_LABELS,ACTION_LABELS,showAppModal:options.showAppModal || (async()=>({confirm:true})),setInterval:()=>1,clearInterval:()=>{}}
  const setup=new Function(...Object.keys(dependencies),`${script}\nreturn {${exports}};`)
  const app=setup(...Object.values(dependencies))
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

test('exchange page refreshes authentication and reads real records after login', async () => {
  const h = pageHarness('exchanges')
  h.lifecycle.show()
  assert.equal(h.app.authenticated.value, false)
  h.storage.set(TOKEN_KEY, 'session-a')
  const loaded=h.lifecycle.show()
  assert.equal(h.app.authenticated.value, true)
  h.requests[0].resolve({records:[],page:1,total:0});await loaded
  h.storage.delete(TOKEN_KEY)
  h.lifecycle.show()
  assert.equal(h.app.authenticated.value, false)
  h.app.login()
  assert.deepEqual(h.navigations, [{ url: '/pages/login/login?redirect=exchanges' }])
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].url,'/api/exchanges/mine')
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

test('previewing a complete recommendation is read-only until explicit submission', () => {
  const h=pageHarness('matches')
  h.app.previewMatch(recommendation())
  assert.equal(h.requests.length,0)
  assert.equal(h.app.preview.value.id,'pair')
})

test('my exchanges pagination sends the requested server page and uses its count', async () => {
  const h=pageHarness('exchanges');h.storage.set(TOKEN_KEY,'session-a')
  const request=h.app.loadList(2)
  assert.equal(h.requests[0].data.page,2)
  h.requests[0].resolve({records:[{id:22}],total:7,page:2});await request
  assert.deepEqual(h.app.records.value,[{id:22}])
})

function recommendation() {
  return {id:'pair',ruleVersion:'independent-v2',length:2,participants:[{userId:1,itemId:10,itemVersion:3,displayName:'甲'},{userId:2,itemId:20,itemVersion:5,displayName:'乙'}],flows:[{itemId:10,fromUserId:1,toUserId:2,demandId:102,demandVersion:7},{itemId:20,fromUserId:2,toUserId:1,demandId:101,demandVersion:8}]}
}
const tick=()=>new Promise(resolve=>setImmediate(resolve))
function signedIn(h) {h.storage.set(TOKEN_KEY,'session-a');h.storage.set(USER_KEY,{id:1})}

test('creation double click sends one request and retries the original key after an unknown response', async()=>{
  const h=pageHarness('matches');signedIn(h);h.app.previewMatch(recommendation())
  const first=h.app.createExchange();h.app.createExchange();await tick()
  assert.equal(h.requests.length,1);const body=h.requests[0].data
  assert.equal(h.app.creation.value.phase,'uncertain')
  h.requests[0].reject({message:'网络结果未知',uncertain:true});await first
  const loading=h.app.load();h.requests[1].resolve({ruleVersion:'independent-v2',recommendations:[]});await loading
  assert.ok(h.app.creation.value)
  h.app.reviewRetained();const retry=h.app.createExchange(true);await tick()
  assert.deepEqual(h.requests[2].data,body)
  h.requests[2].resolve({id:71});await retry
  assert.deepEqual(h.navigations,[{url:'/pages/exchanges/exchanges?id=71'}])
})

test('unknown original creation cannot display a different proposal above its retry action', async()=>{
  const h=pageHarness('matches');signedIn(h);h.app.previewMatch(recommendation())
  const pending=h.app.createExchange();await tick()
  const other={...recommendation(),id:'other'};other.flows=other.flows.map(f=>({...f,demandVersion:f.demandVersion+1}))
  h.app.previewMatch(other)
  assert.equal(h.app.preview.value.id,'pair')
  h.requests[0].reject({message:'网络结果未知',uncertain:true});await pending
})

test('a creation response after leaving the tab never navigates or erases its saved request', async()=>{
  const h=pageHarness('matches');signedIn(h);h.app.previewMatch(recommendation())
  const pending=h.app.createExchange();await tick();h.lifecycle.hide()
  h.requests[0].resolve({id:71});await pending
  assert.deepEqual(h.navigations,[])
  assert.equal(createExchangeJournal({getStorageSync:key=>h.storage.get(key),setStorageSync:(key,value)=>h.storage.set(key,value)},1).creation().phase,'uncertain')
})

function waiting(version=0) {return {id:71,version,status:'AWAITING_CONFIRMATION',participants:[{userId:1,displayName:'甲',offeredItemId:10,receivedItemId:20}],flows:[],allowedActions:['CONFIRM','CANCEL']}}
test('409 preserves the original action and requires explicit adoption of the refreshed version', async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value=waiting();h.app.targetId.value=71
  h.app.beginAction('CANCEL');h.app.reason.value='课程冲突'
  const submit=h.app.submitAction();await tick()
  assert.deepEqual(h.requests[0].data,{version:0,reason:'课程冲突'})
  h.requests[0].reject({status:409,message:'交换已更新'});await tick()
  h.requests[1].resolve(waiting(1));await submit
  assert.equal(h.app.pending.value.phase,'conflict');assert.equal(h.app.pending.value.request.body.version,0)
  assert.equal(h.app.reason.value,'课程冲突');assert.equal(h.requests.length,2)
  h.app.useCurrentVersion();assert.equal(h.app.pending.value.request.body.version,1)
  assert.equal(h.app.reason.value,'课程冲突');assert.equal(h.app.pending.value.phase,'draft')
})

test('an action response for an old account cannot replace another account detail', async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value=waiting();h.app.targetId.value=71
  h.app.beginAction('CONFIRM');const submit=h.app.submitAction();await tick()
  h.storage.set(TOKEN_KEY,'session-b');h.storage.set(USER_KEY,{id:2});h.app.detail.value={id:999}
  h.requests[0].resolve(waiting(1));await submit
  assert.equal(h.app.detail.value.id,999)
  assert.equal(h.requests.length,1)
})


test('refreshing a draft action preserves entered reason and permits cancelling only the unsent draft', async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value=waiting();h.app.targetId.value=71
  h.app.beginAction('CANCEL');h.app.reason.value='先保留填写的原因'
  const refresh=h.app.refreshActionDetail();h.requests[0].resolve(waiting(1));await refresh
  assert.equal(h.app.pending.value.phase,'draft');assert.equal(h.app.pending.value.request.body.version,0)
  assert.equal(h.app.reason.value,'先保留填写的原因')
  h.app.dismissDraft();assert.equal(h.app.pending.value,null)
  h.app.beginAction('CONFIRM');assert.equal(h.app.pending.value.action,'CONFIRM')
})

test('a late successful action after page hide does not refresh hidden views or discard recovery', async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value=waiting();h.app.targetId.value=71
  h.app.beginAction('CONFIRM');const submit=h.app.submitAction();await tick();h.lifecycle.hide()
  h.requests[0].resolve(waiting(1));await submit
  assert.equal(h.requests.length,1)
  assert.equal(h.app.detail.value.version,0)
  assert.equal(h.app.pending.value.phase,'uncertain')
})

test('unknown action recovery remains an exact request and cannot adopt a newer version or be dismissed', async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value=waiting();h.app.targetId.value=71
  h.app.beginAction('CANCEL');h.app.reason.value='课表变化'
  const first=h.app.submitAction();await tick();h.requests[0].reject({message:'结果未知',uncertain:true});await first
  h.app.detail.value=waiting(1);h.app.useCurrentVersion();h.app.dismissDraft()
  assert.equal(h.app.pending.value.phase,'uncertain');assert.equal(h.app.pending.value.request.body.version,0)
  const retry=h.app.submitAction();await tick()
  assert.deepEqual(h.requests[1].data,h.requests[0].data)
  h.requests[1].reject({message:'仍未收到结果',uncertain:true});await retry
})

test('server actions are the only entry to a new handoff or cancellation',()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value={...waiting(),allowedActions:['RECEIVED','DISPUTE']}
  h.app.beginAction('CANCEL');assert.equal(h.app.pending.value,null)
  h.app.beginAction('RECEIVED');assert.equal(h.app.pending.value.action,'RECEIVED')
})

test('exchange session expiry keeps a safe detail target for the login route',async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.targetId.value=71
  const load=h.app.loadDetail();h.storage.delete(TOKEN_KEY);h.storage.delete(USER_KEY)
  h.requests[0].reject({status:401,message:'已过期'});await load
  assert.equal(h.app.authenticated.value,false);assert.equal(h.app.detail.value,null)
  h.app.login();assert.equal(h.navigations[0].url,'/pages/login/login?redirect=exchanges&exchangeId=71&reason=session-expired')
})

const storedJournal=h=>createExchangeJournal({getStorageSync:key=>h.storage.get(key),setStorageSync:(key,value)=>h.storage.set(key,value)},1)
function replacementRecommendation() {
  const value=recommendation();value.id='replacement'
  value.participants=value.participants.map(person=>({...person,itemId:person.itemId+100}))
  value.flows=value.flows.map(flow=>({...flow,itemId:flow.itemId+100,demandId:flow.demandId+100}))
  return value
}
test('a creation replaced while the confirmation modal is open is restored without sending the old request',async()=>{
  let confirm
  const h=pageHarness('matches',{showAppModal:()=>new Promise(resolve=>{confirm=resolve})});signedIn(h)
  h.app.previewMatch(recommendation());const submit=h.app.createExchange()
  const other=storedJournal(h),original=other.creation();other.clearCreation(original.body.idempotencyKey)
  const replacement=other.prepare(replacementRecommendation())
  confirm({confirm:true});await submit
  assert.equal(h.requests.length,0);assert.deepEqual(other.creation(),replacement)
  assert.equal(h.app.preview.value.id,'replacement');assert.match(h.app.createError.value,/其他页面更新/)
})
for(const status of [200,409]) test(`a late creation ${status} cannot overwrite a newer retained proposal`,async()=>{
  const h=pageHarness('matches');signedIn(h);h.app.previewMatch(recommendation())
  const submit=h.app.createExchange();await tick()
  const other=storedJournal(h),original=other.creation()
  other.updateCreation(original.body.idempotencyKey,{phase:'confirmed',exchangeId:71})
  other.clearCreation(original.body.idempotencyKey)
  const replacement=other.prepare(replacementRecommendation())
  if(status===200)h.requests[0].resolve({id:71});else h.requests[0].reject({status,message:'旧请求冲突'})
  await submit
  assert.equal(h.requests.length,1);assert.deepEqual(h.navigations,[]);assert.deepEqual(other.creation(),replacement)
  assert.equal(h.app.preview.value.id,'replacement');assert.equal(h.app.creation.value.body.idempotencyKey,replacement.body.idempotencyKey)
})
test('retry checks the key of the displayed proposal before reading a different tab record',async()=>{
  const h=pageHarness('matches');signedIn(h);h.app.previewMatch(recommendation())
  const submit=h.app.createExchange();await tick();h.requests[0].reject({message:'结果未知'});await submit
  const other=storedJournal(h),original=other.creation()
  other.updateCreation(original.body.idempotencyKey,{phase:'rejected'});other.clearCreation(original.body.idempotencyKey)
  const replacement=other.prepare(replacementRecommendation())
  h.app.reviewRetained();await h.app.createExchange(true)
  assert.equal(h.requests.length,1);assert.equal(h.app.preview.value.id,'replacement');assert.deepEqual(other.creation(),replacement)
})
test('an action created in another tab while the modal is open is preserved without sending a stale action',async()=>{
  let confirm
  const h=pageHarness('exchanges',{showAppModal:()=>new Promise(resolve=>{confirm=resolve})});signedIn(h)
  h.app.detail.value=waiting();h.app.targetId.value=71;h.app.beginAction('CONFIRM')
  const submit=h.app.submitAction(),other=storedJournal(h)
  const replacement=other.saveAction(71,{action:'CANCEL',exchangeId:71,phase:'uncertain',request:actionRequest('CANCEL',0,{reason:'另一页面取消'})})
  confirm({confirm:true});await submit
  assert.equal(h.requests.length,0);assert.deepEqual(other.action(71),replacement)
  assert.equal(h.app.pending.value.action,'CANCEL');assert.equal(h.app.reason.value,'另一页面取消')
})
for(const status of [200,409]) test(`a late action ${status} cannot discard another tab's unknown cancellation`,async()=>{
  const h=pageHarness('exchanges');signedIn(h);h.app.detail.value=waiting();h.app.targetId.value=71
  h.app.beginAction('CONFIRM');const submit=h.app.submitAction();await tick()
  const other=storedJournal(h),original=other.action(71)
  other.clearAction(71,original.journalRevision)
  const replacement=other.saveAction(71,{action:'CANCEL',exchangeId:71,phase:'uncertain',request:actionRequest('CANCEL',1,{reason:'更新后的取消原因'})})
  if(status===200)h.requests[0].resolve(waiting(1));else h.requests[0].reject({status,message:'旧确认版本冲突'})
  await submit
  assert.equal(h.requests.length,1);assert.deepEqual(other.action(71),replacement)
  assert.equal(h.app.pending.value.action,'CANCEL');assert.equal(h.app.pending.value.phase,'uncertain')
  assert.equal(h.app.reason.value,'更新后的取消原因')
})

test('newer detail reapplies the status filter and returns to the last valid page when a completed row disappears',async()=>{
  const h=pageHarness('exchanges');signedIn(h)
  h.app.filter.value=EXCHANGE_STATUSES.indexOf('READY');h.app.page.value=2;h.app.total.value=7
  h.app.records.value=[{...waiting(8),status:'READY'}];h.app.targetId.value=71
  const refresh=h.app.loadDetail()
  h.requests[0].resolve({...waiting(9),status:'COMPLETED',allowedActions:[]});await tick()
  assert.equal(h.app.detail.value.status,'COMPLETED')
  assert.equal(h.requests[1].url,'/api/exchanges/mine')
  assert.deepEqual(h.requests[1].data,{page:2,size:6,status:'READY'})
  h.requests[1].resolve({records:[],page:2,size:6,total:6});await tick()
  assert.deepEqual(h.requests[2].data,{page:1,size:6,status:'READY'})
  const remaining={...waiting(3),id:72,status:'READY'}
  h.requests[2].resolve({records:[remaining],page:1,size:6,total:6});await refresh
  assert.deepEqual(h.app.records.value,[remaining]);assert.equal(h.app.total.value,6)
  assert.equal(h.app.page.value,1);assert.equal(h.app.filter.value,EXCHANGE_STATUSES.indexOf('READY'))
})
test('a detail version change also refreshes the list when the status stays the same',async()=>{
  const h=pageHarness('exchanges');signedIn(h)
  h.app.records.value=[waiting(0)];h.app.targetId.value=71
  const refresh=h.app.loadDetail();h.requests[0].resolve(waiting(1));await tick()
  assert.equal(h.requests[1].url,'/api/exchanges/mine')
  h.requests[1].resolve({records:[waiting(1)],page:1,size:6,total:1});await refresh
  assert.equal(h.app.records.value[0].version,1)
  const unchanged=h.app.loadDetail();h.requests[2].resolve(waiting(1));await unchanged
  assert.equal(h.requests.length,3)
})
