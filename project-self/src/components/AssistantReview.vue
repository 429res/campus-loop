<script setup>
import {ref,watch} from 'vue'
import http from '@/http'
const props=defineProps({endpoint:{type:String,required:true}})
const busy=ref(false),result=ref(null),error=ref('');let sequence=0
watch(()=>props.endpoint,()=>{sequence++;result.value=null;error.value='';busy.value=false})
async function assist(){const run=++sequence;busy.value=true;error.value='';result.value=null;try{const {data}=await http.post(props.endpoint,{}, {silent:true,timeout:120000});if(run===sequence)result.value=data}catch(e){if(run===sequence)error.value=e.response?.data?.msg||e.message}finally{if(run===sequence)busy.value=false}}
</script>
<template><section class="assistant-review"><strong>千问审核助手</strong><p>将当前内容交给千问分析，返回建议及待核实事项。不会自动通过、隐藏或处罚。</p><el-button :loading="busy" :disabled="busy" @click="assist">分析内容与举报</el-button><el-alert v-if="error" :title="error" type="warning" :closable="false"/><template v-if="result"><el-tag>AI 建议：{{({REVIEW:'人工核实',APPROVE:'可考虑通过',REJECT:'建议进一步处理'})[result.decision]}}</el-tag><p class="reason">{{result.reason}}</p><ul><li v-for="(check,index) in result.checks" :key="index">{{check}}</li></ul><small>由 {{result.model}} 生成，请结合原文和图片独立判断。</small></template></section></template>
<style scoped>.assistant-review{display:flex;flex-direction:column;align-items:flex-start;gap:12px;background:var(--cl-blue-soft);padding:18px;border-radius:14px;margin:18px 0}.assistant-review p{margin:0;line-height:1.8;font-size:13px}.reason{white-space:pre-wrap}.assistant-review li{font-size:13px;line-height:1.8}</style>
