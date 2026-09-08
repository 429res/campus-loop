const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { ref } = require('vue')
const script = fs.readFileSync(path.join(__dirname, '../src/pages/detail/detail.vue'),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
function harness(mine, favorites = {}) {
  let token = 'first-session', onLoad, onUnload
  const requests = [], writes = []
  const setup = new Function('ref','onLoad','onShow','onPullDownRefresh','onUnload','http','uni','TOKEN_KEY','isAbortError','favoriteIds','readAllFavorites','setFavorite',`${script}\nreturn {load,item,error,toggleFavorite,favorited,favoriteError};`)
  const app = setup(ref, cb => onLoad=cb, () => {}, () => {}, cb => onUnload=cb, {get:url=>new Promise((resolve,reject)=>requests.push({url,resolve,reject}))}, {getStorageSync:()=>token}, 'token', ()=>false,
    records => new Set(records.map(record => String(record.itemId))),
    favorites.read || (async () => []),
    (id, value) => { writes.push({id,value}); return favorites.write?.(id,value) || Promise.resolve() })
  onLoad({id:'12',mine:mine?'1':'0'})
  return {app,requests,writes,unload:()=>onUnload(),setToken:value=>token=value}
}
for (const mine of [true,false]) test(`detail uses explicit ${mine?'private':'public'} endpoint without visibility fallback`,async()=>{
  const h=harness(mine), loading=h.app.load()
  assert.equal(h.requests[0].url,`/api/items/${mine?'mine/':''}12`)
  h.requests[0].reject({status:404,message:'Unavailable'}); await loading
  assert.equal(h.requests.length,1);assert.equal(h.app.item.value,null)
})
test('late private response after session replacement cannot display the previous owner content',async()=>{
  const h=harness(true), loading=h.app.load(); h.setToken('replacement-session')
  h.requests[0].resolve({id:12,description:'private'});await loading
  assert.equal(h.app.item.value,null)
})
test('latest private response wins when refresh responses arrive out of order',async()=>{
  const h=harness(true), first=h.app.load(), second=h.app.load()
  h.requests[1].resolve({id:12,status:'REJECTED'});await second
  h.requests[0].resolve({id:12,status:'PENDING_REVIEW'});await first
  assert.equal(h.app.item.value.status,'REJECTED')
})
async function loadItem(h, status) {
  const loading = h.app.load()
  h.requests.at(-1).resolve({id:12,status,reviewReason:'Please clarify the condition'})
  await loading
}
test('public detail reads favorite state and supports add then remove',async()=>{
  const h = harness(false)
  await loadItem(h, 'AVAILABLE')
  await h.app.toggleFavorite(); assert.equal(h.app.favorited.value,true)
  await h.app.toggleFavorite(); assert.equal(h.app.favorited.value,false)
  assert.deepEqual(h.writes,[{id:'12',value:true},{id:'12',value:false}])
  assert.equal(h.app.favoriteError.value,'')
})
for (const status of ['PENDING_REVIEW','REJECTED']) test(`${status} detail blocks a new favorite but allows cancellation of an existing relation`,async()=>{
  const h = harness(true)
  await loadItem(h,status); await h.app.toggleFavorite()
  assert.deepEqual(h.writes,[])
  const saved = harness(true,{read:async()=>[{itemId:12,itemVisible:false,item:null}]})
  await loadItem(saved,status); await saved.app.toggleFavorite()
  assert.deepEqual(saved.writes,[{id:'12',value:false}])
  assert.equal(saved.app.item.value.reviewReason,'Please clarify the condition')
})
for (const invalidate of ['refresh','session','unload']) test(`late favorite read is ignored after ${invalidate}`,async()=>{
  const reads = []
  const h = harness(true,{read:()=>new Promise(resolve=>reads.push(resolve))})
  const first = h.app.load()
  h.requests[0].resolve({id:12,status:'AVAILABLE'})
  await Promise.resolve()
  if (invalidate === 'refresh') {
    const second = h.app.load()
    h.requests[1].resolve({id:12,status:'REJECTED'})
    await Promise.resolve(); reads[1]([]); await second
  } else if (invalidate === 'session') h.setToken('replacement-session')
  else h.unload()
  reads[0]([{itemId:12}]); await first
  assert.equal(h.app.favorited.value,false)
})
test('uncertain favorite write is submitted once and reconciled with a read',async()=>{
  let reads = 0, rejectWrite
  const h = harness(false,{
    read:async()=>++reads === 1 ? [] : [{itemId:12}],
    write:()=>new Promise((resolve,reject)=>rejectWrite=reject)
  })
  await loadItem(h,'AVAILABLE')
  const saving = h.app.toggleFavorite()
  await h.app.toggleFavorite()
  assert.equal(h.writes.length,1)
  rejectWrite({uncertain:true,message:'Timeout'}); await saving
  assert.equal(reads,2); assert.equal(h.writes.length,1)
  assert.equal(h.app.favorited.value,true)
  assert.match(h.app.favoriteError.value,/已重新读取收藏状态/)
})
test('favorite write completion from an old session cannot update favorite state',async()=>{
  let resolveWrite
  const h = harness(false,{write:()=>new Promise(resolve=>resolveWrite=resolve)})
  await loadItem(h,'AVAILABLE')
  const saving = h.app.toggleFavorite(); h.setToken('replacement-session')
  resolveWrite(); await saving
  assert.equal(h.app.favorited.value,false)
})
