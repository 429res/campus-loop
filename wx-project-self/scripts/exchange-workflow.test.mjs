import assert from 'node:assert/strict'
import { test } from 'node:test'
import { actionRequest, createExchangeJournal, creationSnapshot, expiryText } from '../src/common/exchange-workflow.mjs'
const match=()=>({ruleVersion:'independent-v2',participants:[{userId:1,itemId:30,itemVersion:4},{userId:2,itemId:10,itemVersion:6},{userId:3,itemId:20,itemVersion:8}],flows:[{itemId:30,fromUserId:1,toUserId:2,demandId:102,demandVersion:2},{itemId:10,fromUserId:2,toUserId:3,demandId:103,demandVersion:3},{itemId:20,fromUserId:3,toUserId:1,demandId:101,demandVersion:1}]})
const storage=()=>{const values=new Map();return {getStorageSync:key=>values.get(key),setStorageSync:(key,value)=>values.set(key,value)}}
test('three-way creation rotates the server direction with precise paired demand versions',()=>{
  assert.deepEqual(creationSnapshot(match()).flows,[{itemId:10,itemVersion:6,demandId:103,demandVersion:3},{itemId:20,itemVersion:8,demandId:101,demandVersion:1},{itemId:30,itemVersion:4,demandId:102,demandVersion:2}])
})
test('missing versions and broken directed cycles cannot be filled with guessed data',()=>{
  const missing=match();delete missing.participants[0].itemVersion;assert.throws(()=>creationSnapshot(missing))
  const broken=match();broken.flows[1].toUserId=2;assert.throws(()=>creationSnapshot(broken))
})
test('a persisted unknown creation survives a new journal and cannot be discarded or replaced',()=>{
  const disk=storage(),first=createExchangeJournal(disk,1), original=first.prepare(match());first.updateCreation(original.body.idempotencyKey,{phase:'uncertain'})
  const restored=createExchangeJournal(disk,1);assert.deepEqual(restored.prepare(match()).body,original.body)
  assert.throws(()=>restored.clearCreation(original.body.idempotencyKey))
  const changed=match();changed.participants[0].itemVersion++
  assert.throws(()=>restored.prepare(changed))
  assert.equal(createExchangeJournal(disk,2).creation(),null)
})
test('confirmed creation replay keeps its key while a separately chosen new snapshot receives a new key',()=>{
  const journal=createExchangeJournal(storage(),1),original=journal.prepare(match());journal.updateCreation(original.body.idempotencyKey,{phase:'confirmed',exchangeId:41})
  assert.equal(journal.prepare(match()).body.idempotencyKey,original.body.idempotencyKey)
  const changed=match();changed.participants[0].itemVersion++
  assert.notEqual(journal.prepare(changed).body.idempotencyKey,original.body.idempotencyKey)
})
test('stale creation writers cannot mark or remove a replacement prepared in another tab',()=>{
  const disk=storage(),first=createExchangeJournal(disk,1),second=createExchangeJournal(disk,1)
  const original=first.prepare(match());second.clearCreation(original.body.idempotencyKey)
  const changed=match();changed.participants[0].itemVersion++
  const replacement=second.prepare(changed)
  for(const operation of [()=>first.requireCreation(original.body.idempotencyKey),
    ()=>first.updateCreation(original.body.idempotencyKey,{phase:'uncertain'}),
    ()=>first.updateCreation(original.body.idempotencyKey,{phase:'confirmed',exchangeId:41}),
    ()=>first.updateCreation(original.body.idempotencyKey,{phase:'rejected'}),
    ()=>first.clearCreation(original.body.idempotencyKey)]) {
    assert.throws(operation,{code:'JOURNAL_CHANGED'});assert.deepEqual(second.creation(),replacement)
  }
})
test('action revisions preserve a newer unknown request against late acknowledgement or conflict writers',()=>{
  const disk=storage(),first=createExchangeJournal(disk,1),second=createExchangeJournal(disk,1)
  const original=first.saveAction(41,{action:'CONFIRM',exchangeId:41,phase:'uncertain',request:actionRequest('CONFIRM',0)})
  second.clearAction(41,original.journalRevision)
  const replacement=second.saveAction(41,{action:'CANCEL',exchangeId:41,phase:'uncertain',request:actionRequest('CANCEL',1,{reason:'新的取消原因'})})
  assert.throws(()=>first.clearAction(41,original.journalRevision),{code:'JOURNAL_CHANGED'})
  assert.throws(()=>first.saveAction(41,{...original,phase:'conflict'}),{code:'JOURNAL_CHANGED'})
  assert.deepEqual(second.action(41),replacement)
})
test('handoff requires affirmative acknowledgement and cancellation/dispute require nonblank reasons',()=>{
  assert.throws(()=>actionRequest('RECEIVED',2,{acknowledged:false}))
  assert.throws(()=>actionRequest('CANCEL',2,{reason:' '}));assert.throws(()=>actionRequest('DISPUTE',2))
  assert.deepEqual(actionRequest('RECEIVED',2,{acknowledged:true,reason:' 当面收到 '}),{path:'handoff',body:{version:2,kind:'RECEIVED',acknowledged:true,note:'当面收到'}})
})
test('client countdown never changes server status and handoff removes its expiry assumption',()=>{
  const exchange={status:'READY',expiresAt:'2020-01-01T00:00:00Z',participants:[]}
  assert.match(expiryText(exchange),/刷新服务器/);assert.equal(exchange.status,'READY')
  exchange.participants=[{receivedAt:'2019-12-31T00:00:00Z'}];assert.match(expiryText(exchange),/交接已开始/)
})
