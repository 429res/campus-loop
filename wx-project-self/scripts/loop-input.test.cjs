const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {ref,computed}=require('vue');
const source=fs.readFileSync(path.join(__dirname,'../src/components/LoopInput.vue'),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'');
function harness(multiline=false){const events=[];const app=new Function('ref','computed','defineOptions','defineProps','defineEmits',source+';return {input,compositionEnd,composing,confirm,value};')(ref,computed,()=>{},()=>({modelValue:'',multiline}),()=>((...args)=>events.push(args)));return {app,events};}
test('H5 composition does not emit partial input or submit before Chinese text is committed',()=>{
 const h=harness();h.app.composing.value=true;h.app.input({target:{value:'zhong'}});h.app.confirm({target:{value:'zhong'},isComposing:true});assert.equal(h.events.length,0);
 h.app.compositionEnd({target:{value:'中文'}});assert.deepEqual(h.events,[['update:modelValue','中文']]);
 h.app.confirm({target:{value:'中文'},isComposing:false});assert.deepEqual(h.events[1],['confirm',{detail:{value:'中文'}}]);
});
test('multiline Enter remains an editing action and native-platform v-model emits updates',()=>{
 const h=harness(true);h.app.confirm({target:{value:'line'},isComposing:false});assert.equal(h.events.length,0);
 h.app.value.value='native input';assert.deepEqual(h.events,[['update:modelValue','native input']]);
});
