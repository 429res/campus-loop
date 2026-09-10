const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {ref,reactive,computed}=require('vue');
const {loginDestination}=require('../src/common/navigation.mjs');
const script=name=>fs.readFileSync(path.join(__dirname,`../src/pages/${name}/${name}.vue`),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'');
function harness(name,expose) {
  let token='session-a',onLoad,hidden,unloaded;
  const requests=[],cache={},navigation=[],modals=[];
  const request=(url,body)=>new Promise((resolve,reject)=>requests.push({url,body,resolve,reject}));
  const uni={getStorageSync:()=>token,setStorageSync:(k,v)=>cache[k]=v,redirectTo:o=>navigation.push(o.url),navigateTo:o=>navigation.push(o.url),switchTab:o=>navigation.push(o.url),showToast:()=>{}};
  const args={ref,reactive,computed,loginDestination,onShow:()=>{},onHide:fn=>hidden=fn,onUnload:fn=>unloaded=fn,onLoad:fn=>onLoad=fn,http:{get:(url,body)=>url==='/api/notifications/unread-count'?Promise.resolve(0):request(url,body),put:request,patch:request,post:request,delete:request},uni,TOKEN_KEY:'token',USER_KEY:'user',clearSession:()=>token='',showAppModal:options=>new Promise(resolve=>modals.push({options,resolve}))};
  const app=new Function(...Object.keys(args),script(name)+`;return {${expose}};`)(...Object.values(args));
  return {app,requests,cache,navigation,modals,setToken:v=>token=v,token:()=>token,options:o=>onLoad(o),hide:()=>hidden(),unload:()=>unloaded()};
}
const profile=()=>harness('profile','load,user,profileForm,saveProfile,passwordForm,changePassword,logout,profileUncertain');
const user={id:1,displayName:'First user',version:0};
async function loginProfile(h){const loading=h.app.load();h.requests.at(-1).resolve(user);await loading;}
for(const token of ['session-b','']) test(`profile response cannot restore old identity after ${token?'account switch':'logout'}`,async()=>{
 const h=profile(),pending=h.app.load();h.setToken(token);h.requests[0].resolve(user);await pending;
 assert.equal(h.app.user.value,null);assert.equal(h.cache.user,undefined);
});
test('profile refresh responses respect request ordering',async()=>{
 const h=profile(),first=h.app.load(),second=h.app.load();h.requests[1].resolve({id:1,displayName:'Latest'});await second;h.requests[0].resolve(user);await first;
 assert.equal(h.app.user.value.displayName,'Latest');
});
test('old profile save cannot replace a newly logged-in account',async()=>{
 const h=profile();await loginProfile(h);h.app.profileForm.displayName='Changed';const saving=h.app.saveProfile();h.setToken('session-b');const loading=h.app.load();
 h.requests[2].resolve({id:2,displayName:'Second user'});await loading;h.requests[1].resolve({id:1,displayName:'Changed'});await saving;
 assert.equal(h.cache.user.id,2);assert.equal(h.app.user.value.id,2);
});
test('old password response cannot clear a replacement session',async()=>{
 const h=profile();await loginProfile(h);Object.assign(h.app.passwordForm,{currentPassword:'synthetic-old',newPassword:'synthetic-new',confirmPassword:'synthetic-new'});
 const changing=h.app.changePassword();h.modals[0].resolve({confirm:true});await Promise.resolve();h.setToken('session-b');h.requests[1].resolve(null);await changing;
 assert.equal(h.token(),'session-b');assert.equal(h.navigation.length,0);
});
test('password confirmation cannot submit old form using a replacement session',async()=>{
 const h=profile();await loginProfile(h);Object.assign(h.app.passwordForm,{currentPassword:'synthetic-old',newPassword:'synthetic-new',confirmPassword:'synthetic-new'});
 const changing=h.app.changePassword();h.setToken('session-b');h.modals[0].resolve({confirm:true});await changing;assert.equal(h.requests.length,1);
});
const demands=()=>harness('demands','init,loadDemands,loadOfferable,openEdit,demands,offerable,itemCache,form,editorOpen,applyDemand,saveDemand,categories');
for(const action of ['loadDemands','loadOfferable','openEdit']) test(`${action} discards old private data after account change`,async()=>{
 const h=demands();const pending=h.app[action]({id:1});h.setToken('session-b');h.requests[0].resolve(action==='openEdit'?{id:1,offeredItems:[]}:{records:[{id:1,itemId:1,title:'private'}],total:1,page:1});await pending;
 assert.equal(h.app.demands.value.length,0);assert.equal(h.app.offerable.value.length,0);assert.equal(h.app.editorOpen.value,false);assert.equal(Object.keys(h.app.itemCache.value).length,0);
});
test('returning with another account clears private editor and cached candidates even if new reads fail',async()=>{
 const h=demands();h.app.applyDemand({id:1,description:'private',offeredItems:[{itemId:1,title:'private'}]});h.app.editorOpen.value=true;h.setToken('session-b');const pending=h.app.init();
 assert.equal(h.app.form.id,null);assert.equal(h.app.editorOpen.value,false);assert.equal(Object.keys(h.app.itemCache.value).length,0);
 h.requests.forEach(r=>r.reject({status:503,message:'Unavailable'}));await pending;
 assert.equal(h.app.form.description,'');
});
test('late old demand 401 cannot redirect a newly logged-in account',async()=>{
 const h=demands(),pending=h.app.loadDemands();h.setToken('session-b');h.requests[0].reject({status:401});await pending;assert.equal(h.navigation.length,0);
});
test('registration preserves the demand return path and never repeats an uncertain write',async()=>{
 const h=harness('register','form,register,uncertain,goLogin');h.options({redirect:'demands'});Object.assign(h.app.form,{username:'synthetic-user',displayName:'Synthetic',password:'synthetic-pass',confirmPassword:'synthetic-pass'});
 const pending=h.app.register();h.requests[0].reject({uncertain:true});await pending;await h.app.register();assert.equal(h.requests.length,1);assert.equal(h.app.form.password,'');h.app.goLogin('registration-unknown');assert.match(h.navigation[0],/redirect=demands/);
});
test('registration route exists without replacing the home launch page',()=>{
 const pages=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/pages.json'),'utf8')).pages;
 assert.equal(pages[0].path,'pages/home/home');assert.ok(pages.some(p=>p.path==='pages/register/register'));
});
