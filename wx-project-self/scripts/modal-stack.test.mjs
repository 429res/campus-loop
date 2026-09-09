import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

// Execute the actual H5 wrapper with a deterministic native-modal/DOM boundary.
function harness({synchronous=false,fail=false}={}) {
  const source=readFileSync(new URL('../src/common/modal.js',import.meta.url),'utf8').replace(/^import .*$/gm,'').replaceAll('export const ','const ')
  const listeners=new Map(),frames=new Map(),calls=[]
  let locks=0,frame=0,options
  const document={activeElement:null,body:{},querySelector:selector=>selector==='uni-modal .uni-modal'?modal:sheet,addEventListener:(name,fn,capture)=>listeners.set(name,{fn,capture}),removeEventListener:name=>listeners.delete(name)}
  const node=()=>({isConnected:true,disabled:false,setAttribute(){},getAttribute:()=>null,closest:selector=>selector==='[inert]'?null:sheet,focus(){document.activeElement=this}})
  const sheet=node(),previous=node();document.activeElement=previous
  const cancel={...node(),label:'cancel',click(){options.success({confirm:false,cancel:true});options.complete({confirm:false,cancel:true})}}
  const confirm={...node(),label:'confirm',click(){options.success({confirm:true,cancel:false});options.complete({confirm:true,cancel:false})}}
  const modal={setAttribute(){},querySelectorAll:selector=>selector==='.uni-modal__btn'?(options.showCancel===false?[confirm]:[cancel,confirm]):[]}
  const uni={showModal:value=>{options=value;calls.push(value);if(fail){value.fail({});value.complete({})}else if(synchronous){value.success({confirm:false,cancel:true});value.complete({confirm:false,cancel:true})}}}
  const dependencies={nextTick:callback=>Promise.resolve().then(callback),acquireOverlayLock:()=>{locks++;let released=false;return()=>{if(!released){released=true;locks--}}},document,uni,requestAnimationFrame:callback=>{frames.set(++frame,callback);return frame},cancelAnimationFrame:id=>frames.delete(id)}
  const app=new Function(...Object.keys(dependencies),`${source}\nreturn {showAppModal,isAppModalOpen}`)(...Object.values(dependencies))
  const flush=async()=>{await Promise.resolve();for(const [id,callback] of [...frames]){frames.delete(id);callback()}await Promise.resolve()}
  const key=(key,extra={})=>{const event={key,prevented:false,stopped:false,preventDefault(){this.prevented=true},stopImmediatePropagation(){this.stopped=true},...extra};listeners.get('keydown')?.fn(event);return event}
  return {app,document,sheet,previous,cancel,confirm,calls,listeners,flush,key,locks:()=>locks}
}

test('nested native confirmation captures keyboard above sheets and restores focus/lock after cancellation',async()=>{
  const h=harness();const first=h.app.showAppModal({title:'确认'}),again=h.app.showAppModal({title:'重复'})
  assert.equal(first,again);assert.equal(h.calls.length,1);assert.equal(h.locks(),1);await h.flush()
  assert.equal(h.listeners.get('keydown').capture,true);assert.equal(h.document.activeElement,h.cancel)
  assert.equal(h.key('Tab').stopped,true);assert.equal(h.document.activeElement,h.confirm)
  h.key('Tab');assert.equal(h.document.activeElement,h.cancel)
  h.key('Tab',{shiftKey:true});assert.equal(h.document.activeElement,h.confirm)
  const escape=h.key('Escape');assert.equal(escape.stopped,true);assert.equal(escape.prevented,true)
  assert.equal((await first).cancel,true);await h.flush()
  assert.equal(h.app.isAppModalOpen(),false);assert.equal(h.listeners.size,0);assert.equal(h.locks(),0);assert.equal(h.document.activeElement,h.previous)
})

test('Escape never confirms a modal whose cancel button is unavailable',async()=>{
  const h=harness(),promise=h.app.showAppModal({showCancel:false});await h.flush()
  const escape=h.key('Escape');assert.equal(escape.stopped,true);assert.equal(h.app.isAppModalOpen(),true)
  h.key('Enter',{repeat:true});assert.equal(h.app.isAppModalOpen(),true)
  h.key('Enter',{isComposing:true});assert.equal(h.app.isAppModalOpen(),true)
  h.key('Enter');assert.equal((await promise).confirm,true)
})

test('closing before the scheduled render does not install a late focus trap or leak a lock',async()=>{
  const h=harness({synchronous:true});assert.equal((await h.app.showAppModal()).cancel,true);await h.flush()
  assert.equal(h.listeners.size,0);assert.equal(h.locks(),0);assert.equal(h.app.isAppModalOpen(),false)
})

test('native modal failure resolves without confirmation and permits a subsequent modal',async()=>{
  const h=harness({fail:true});assert.equal((await h.app.showAppModal()).confirm,false);await h.flush()
  assert.equal(h.locks(),0);assert.equal(h.listeners.size,0)
  await h.app.showAppModal();assert.equal(h.calls.length,2)
})

test('an unavailable triggering control falls back to the still-open sheet for focus restoration',async()=>{
  const h=harness();const promise=h.app.showAppModal();await h.flush();h.previous.disabled=true
  h.key('Escape');await promise;await h.flush();assert.equal(h.document.activeElement,h.sheet)
})
