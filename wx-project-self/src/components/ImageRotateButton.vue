<script setup>
import {ref,onBeforeUnmount} from 'vue'
import LoopButton from './LoopButton.vue'
import http,{TOKEN_KEY} from '../common/http'
const props=defineProps({url:String,disabled:Boolean})
const emit=defineEmits(['rotated','busy'])
const busy=ref(false),error=ref('');let alive=true
async function rotate(){
 if(busy.value||props.disabled||!props.url)return
 const token=uni.getStorageSync(TOKEN_KEY),original=props.url
 busy.value=true;error.value='';emit('busy',true)
 try{const result=await http.post('/api/uploads/rotate',{url:original},{silent:true});if(alive&&token===uni.getStorageSync(TOKEN_KEY)&&props.url===original)emit('rotated',result.url)}
 catch(e){if(alive&&token===uni.getStorageSync(TOKEN_KEY))error.value=e.message}
 finally{busy.value=false;if(alive)emit('busy',false)}
}
onBeforeUnmount(()=>{alive=false;emit('busy',false)})
</script>
<template><view class="rotate-control"><LoopButton class="cl-btn" :disabled="disabled||busy" aria-label="顺时针旋转图片90度" @click="rotate">{{busy?'旋转中…':'旋转 90°'}}</LoopButton><text v-if="error" class="cl-error" role="alert">{{error}}</text></view></template>
<style scoped>.rotate-control{display:flex;flex-direction:column;gap:6px}.rotate-control .cl-btn{font-size:12px;min-height:32px;padding:6px 12px}</style>
