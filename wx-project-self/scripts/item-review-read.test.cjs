const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { ref } = require('vue')
const script = fs.readFileSync(path.join(__dirname, '../src/pages/detail/detail.vue'),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
function harness(mine) {
  let token = 'first-session', onLoad
  const requests = []
  const setup = new Function('ref','onLoad','onShow','onPullDownRefresh','onUnload','http','uni','TOKEN_KEY','isAbortError',`${script}\nreturn {load,item,error};`)
  const app = setup(ref, cb => onLoad=cb, () => {}, () => {}, () => {}, {get:url=>new Promise((resolve,reject)=>requests.push({url,resolve,reject}))}, {getStorageSync:()=>token}, 'token', ()=>false)
  onLoad({id:'12',mine:mine?'1':'0'})
  return {app,requests,setToken:value=>token=value}
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
