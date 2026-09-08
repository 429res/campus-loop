import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../src/http/index.js',import.meta.url),'utf8').replace(/^import .*$/gm,'').replace(/import\.meta\.env\.VITE_API_BASE_URL/g,'""').replace('export default http;','');
function harness() {
  let token='session-a', request, success, failure, events=0;
  const messages=[];
  const http={interceptors:{request:{use:fn=>request=fn},response:{use:(ok,fail)=>{success=ok;failure=fail}}}};
  const axios={create:()=>http,isCancel:e=>e?.cancelled,CanceledError:class extends Error {cancelled=true}};
  new Function('axios','ElMessage','sessionStorage','window','CustomEvent',source)(axios,{error:m=>messages.push(m)}, {getItem:()=>token,removeItem:()=>token=null},{dispatchEvent:()=>events++},class {});
  return {request,success,failure,messages,setToken:value=>token=value,getToken:()=>token,events:()=>events};
}
test('a late 401 from the previous admin session cannot sign out the new session',async()=>{
  const h=harness(),config=h.request({headers:{}}); h.setToken('session-b');
  await assert.rejects(h.failure({config,response:{status:401}}),e=>e.cancelled);
  assert.equal(h.getToken(),'session-b');assert.equal(h.events(),0);assert.deepEqual(h.messages,[]);
});
test('a late successful admin response is discarded after session replacement',async()=>{
  const h=harness(),config=h.request({headers:{}});h.setToken('session-b');
  await assert.rejects(h.success({config,data:{code:200,data:{private:true}}}),e=>e.cancelled);
});
test('current admin 401 still clears authentication and requests login',async()=>{
  const h=harness(),config=h.request({headers:{}});
  await assert.rejects(h.failure({config,response:{status:401,data:{msg:'expired'}}}));
  assert.equal(h.getToken(),null);assert.equal(h.events(),1);
});
test('a login failure never revokes an existing admin session',async()=>{
  const h=harness(),config=h.request({headers:{},skipAuth:true});
  await assert.rejects(h.failure({config,response:{status:401}}));
  assert.equal(h.getToken(),'session-a');assert.equal(h.events(),0);
});

test('silent read errors remain rejected without duplicate global notifications',async()=>{
  const h=harness(),config=h.request({headers:{},silent:true});
  await assert.rejects(h.failure({config,response:{status:409,data:{msg:'incomplete record'}}}));
  await assert.rejects(h.success({config,data:{code:500,msg:'invalid response'}}));
  assert.deepEqual(h.messages,[]);
  assert.equal(h.getToken(),'session-a');
});

test('silent 401 still revokes the current session and requests login',async()=>{
  const h=harness(),config=h.request({headers:{},silent:true});
  await assert.rejects(h.failure({config,response:{status:401}}));
  assert.equal(h.getToken(),null);assert.equal(h.events(),1);assert.deepEqual(h.messages,[]);
});

test('an obsolete route guard cannot clear a replacement session after refresh cancellation',async()=>{
  let guard,rejectRefresh,cleared=false;
  const auth={token:'session-a',user:null,refresh:()=>new Promise((_,reject)=>rejectRefresh=reject),clear:()=>{cleared=true}};
  const code=readFileSync(new URL('../src/router/index.js',import.meta.url),'utf8').replace(/^import .*$/gm,'').replace(/import\.meta\.env\.DEV/g,'false').replace('export default router;','');
  new Function('createRouter','createWebHistory','useAuth','document',code)(()=>({beforeEach:fn=>guard=fn}),()=>{},()=>auth,{});
  const pending=guard({meta:{title:'Items'},fullPath:'/items'});auth.token='session-b';rejectRefresh(new Error('session replaced'));
  assert.equal(await pending,false);assert.equal(cleared,false);
});
