<script setup>
import {ref,onMounted,onUnmounted} from 'vue'
import http from '@/http'
const props=defineProps({endpoint:{type:String,required:true}})
const emit=defineEmits(['open'])
const data=ref(null),error=ref(''),busy=ref(false);let timer,alive=true
const states={READY:'等待 AI 审核',RUNNING:'AI 正在审核',MANUAL:'需要人工审核',STALE:'内容已更新',APPLIED:'已自动处理'}
async function load(){if(busy.value)return;busy.value=true;try{const result=await http.get(props.endpoint,{silent:true});if(alive){data.value=result.data;error.value=''}}catch(e){if(alive)error.value='自动审核状态暂时不可用，仍可使用下方人工审核。'}finally{if(alive)busy.value=false}}
onMounted(()=>{load();timer=setInterval(load,15000)})
onUnmounted(()=>{alive=false;clearInterval(timer)})
</script>
<template><section class="auto-review panel" aria-label="自动审核与人工接管"><header><div><h2>千问自动审核 <el-tag :type="data?.enabled?'success':'info'">{{ data?.enabled?'已开启':'未开启' }}</el-tag></h2><p>明确结果自动处理；难以判断或服务异常的内容留给你审核。所有结果保留审核记录。</p></div><el-button :loading="busy" @click="load">刷新状态</el-button></header><el-alert v-if="error" :title="error" type="warning" :closable="false"/><div v-for="row in data?.records||[]" :key="row.id" class="auto-row"><el-tag :type="row.state==='MANUAL'?'warning':'info'">{{states[row.state]||row.state}}</el-tag><div><strong>内容 #{{row.target_id}}</strong><p>{{row.reason||'后台处理期间也可以直接人工审核。'}}</p></div><el-button @click="emit('open',{id:row.target_id})">查看并处理</el-button></div><p v-if="data&&!data.records.length">目前没有等待接管的内容。</p></section></template>
<style scoped>.auto-review{margin-bottom:20px}.auto-review header{display:flex;justify-content:space-between;align-items:center;gap:16px}.auto-review h2{font-size:17px;margin:0 0 8px}.auto-review p{font-size:13px;color:var(--cl-text-secondary);line-height:1.6}.auto-row{display:flex;align-items:center;gap:14px;padding:16px 0;border-top:1px solid var(--cl-border)}.auto-row>div{flex:1;min-width:0;overflow-wrap:anywhere}.auto-row p{margin:4px 0}@media(max-width:640px){.auto-review header,.auto-row{flex-wrap:wrap}}</style>
