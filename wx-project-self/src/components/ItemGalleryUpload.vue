<script setup>
import {ref,onBeforeUnmount,nextTick} from 'vue'
import LoopButton from './LoopButton.vue'
import LoopIcon from './LoopIcon.vue'
import http,{TOKEN_KEY,imageUrl,isAbortError} from '../common/http'
const props=defineProps({modelValue:{type:Array,default:()=>[]},disabled:Boolean})
const emit=defineEmits(['update:modelValue','busy'])
const busy=ref(false),error=ref(''),progress=ref(0),pending=ref([])
let operation=0,request,alive=true
function cancel(){operation++;request?.abort?.();request=null;busy.value=false;emit('busy',false)}
async function upload(){if(busy.value||props.disabled)return;const token=uni.getStorageSync(TOKEN_KEY),attempt=++operation;busy.value=true;emit('busy',true);error.value='';const valid=()=>alive&&attempt===operation&&token===uni.getStorageSync(TOKEN_KEY);try{while(pending.value.length&&props.modelValue.length<9){progress.value=0;request=http.upload(pending.value[0],{silent:true,onProgress:p=>{if(valid())progress.value=p}});const result=await request;if(!valid())return;emit('update:modelValue',[...props.modelValue,result.url]);pending.value.shift();await nextTick()}}catch(e){if(valid()&&!isAbortError(e))error.value=e.message}finally{if(attempt===operation){busy.value=false;emit('busy',false)}}}
function choose(){if(props.disabled||busy.value||props.modelValue.length>=9)return;const token=uni.getStorageSync(TOKEN_KEY);uni.chooseImage({count:9-props.modelValue.length,sizeType:['original'],success:r=>{if(!alive||token!==uni.getStorageSync(TOKEN_KEY))return;pending.value=r.tempFilePaths.slice(0,9-props.modelValue.length);upload()}})}
function preview(index){uni.previewImage({urls:props.modelValue.map(u=>imageUrl(u)),current:index})}
function remove(index){if(!busy.value&&!props.disabled)emit('update:modelValue',props.modelValue.filter((_,i)=>i!==index))}
function cover(index){if(busy.value||props.disabled)return;const images=[...props.modelValue];images.unshift(...images.splice(index,1));emit('update:modelValue',images)}
onBeforeUnmount(()=>{alive=false;cancel()})
</script>
<template><view class="gallery-upload"><text class="cl-field-title">物品照片 · {{modelValue.length}} / 9</text><text class="cl-hint">首张为封面；每张最大 10 MB，支持 JPG、PNG、GIF。</text><view class="gallery-grid"><view v-for="(url,index) in modelValue" :key="url" class="gallery-tile"><image :src="imageUrl(url)" mode="aspectFit" @click="preview(index)"/><view class="gallery-tools"><LoopButton class="cl-btn" :disabled="busy||disabled||index===0" @click="cover(index)">{{index===0?'封面':'设为封面'}}</LoopButton><LoopButton class="cl-btn" :disabled="busy||disabled" @click="remove(index)">移除</LoopButton></view></view></view><LoopButton v-if="modelValue.length<9" class="cl-btn" :disabled="busy||disabled" @click="choose"><LoopIcon name="plus"/> 添加照片</LoopButton><view v-if="busy" class="cl-row"><text class="cl-hint">上传中 {{progress}}% · 还剩 {{pending.length}} 张</text><LoopButton class="cl-btn" @click="cancel">取消</LoopButton></view><text v-if="error" class="cl-error" role="alert">{{error}}</text><LoopButton v-if="pending.length&&!busy" class="cl-btn" :disabled="disabled" @click="upload">继续上传剩余照片</LoopButton></view></template>
<style scoped>.gallery-upload{display:flex;flex-direction:column;gap:14px}.gallery-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.gallery-tile{border:1px solid var(--cl-border);border-radius:14px;overflow:hidden}.gallery-tile image{width:100%;height:140px;background:var(--cl-surface-soft)}.gallery-tools{display:flex;justify-content:space-between;padding:6px;gap:4px}.gallery-tools .cl-btn{font-size:11px;padding:6px;min-height:32px}</style>
