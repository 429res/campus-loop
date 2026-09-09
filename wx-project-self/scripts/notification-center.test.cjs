const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs'),vm=require('node:vm'),path=require('node:path')
function harness(){
 const storage=new Map([['token','session-a'],['user',{id:1}]]),requests=[],timers=new Map(),routes=[];let serial=0,cleared
 const source=fs.readFileSync(path.join(__dirname,'../src/common/notification-center.js'),'utf8').replace(/^import .*$/gm,'').replace(/export /g,'')
 const context=vm.createContext({ref:value=>({value}),TOKEN_KEY:'token',USER_KEY:'user',onSessionCleared:fn=>cleared=fn,
 uni:{getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),navigateTo:r=>routes.push(r.url)},
 http:{get:()=>new Promise(resolve=>requests.push(resolve)),patch:async()=>{}},setTimeout:fn=>{timers.set(++serial,fn);return serial},clearTimeout:id=>timers.delete(id)})
 vm.runInContext(source+'\nglobalThis.api={unreadCount,notificationToast,startNotifications,stopNotifications,openNotification};',context)
 return {...context.api,storage,requests,timers,routes,clear:()=>cleared()}
}
const tick=()=>new Promise(resolve=>setImmediate(resolve))
const page=(id=1)=>({total:1,records:[{id,title:'交换邀请',body:'查看方案',link:'/pages/exchanges/exchanges?id=12'}]})
test('polling deduplicates popups and ownership prevents cached pages from spawning timers',async()=>{
 const h=harness();h.startNotifications('first');h.startNotifications('second');h.requests[0](page(9));await tick();assert.equal(h.notificationToast.value,null)
 h.requests[1](page());await tick();assert.equal(h.notificationToast.value.id,1);assert.equal(h.timers.size,1)
 h.stopNotifications('first');assert.equal(h.timers.size,1)
 await h.openNotification(h.notificationToast.value);assert.deepEqual(h.routes,['/pages/exchanges/exchanges?id=12']);assert.equal(h.unreadCount.value,0)
 const next=[...h.timers.values()][0];h.timers.clear();next();h.requests[2](page());await tick();assert.equal(h.notificationToast.value,null)
 h.stopNotifications('second');assert.equal(h.timers.size,0)
})
test('logout clears private notifications immediately and ignores late responses',async()=>{
 const h=harness();h.startNotifications('page');h.requests[0](page());await tick();assert.equal(h.unreadCount.value,1)
 const next=[...h.timers.values()][0];h.timers.clear();next();h.storage.delete('token');h.clear();assert.equal(h.notificationToast.value,null);assert.equal(h.unreadCount.value,0)
 h.requests[1](page(2));await tick();assert.equal(h.notificationToast.value,null);assert.equal(h.timers.size,0)
})
test('account changes cannot receive a previous account response',async()=>{
 const h=harness();h.startNotifications('page');h.storage.set('token','session-b');h.storage.set('user',{id:2});h.startNotifications('page')
 h.requests[0](page(9));await tick();assert.equal(h.unreadCount.value,0)
 h.requests[1](page(10));await tick();assert.equal(h.notificationToast.value.id,10);assert.equal(h.storage.get('campus-notifications-seen:2'),10)
})
