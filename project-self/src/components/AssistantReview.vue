<script setup>
import {ref,watch} from 'vue'
import http from '@/http'
const props=defineProps({endpoint:{type:String,required:true}})
const automatic=ref(null)
async function readAutomatic(endpoint){try{const {data}=await http.get(endpoint.replace('/assist-review','/auto-review'),{silent:true});if(endpoint===props.endpoint)automatic.value=data.latest}catch{automatic.value=null}}
watch(()=>props.endpoint,endpoint=>{automatic.value=null;readAutomatic(endpoint)},{immediate:true})
const busy=ref(false),result=ref(null),error=ref('');let sequence=0
watch(()=>props.endpoint,()=>{sequence++;result.value=null;error.value='';busy.value=false})
async function assist(){const run=++sequence;busy.value=true;error.value='';result.value=null;try{const {data}=await http.post(props.endpoint,{}, {silent:true,timeout:120000});if(run===sequence)result.value=data}catch(e){if(run===sequence)error.value=e.response?.data?.msg||e.message}finally{if(run===sequence)busy.value=false}}
</script>
<template><section class="assistant-review"><template v-if="automatic?.state"><strong>自动审核记录</strong><el-tag :type="automatic.state==='MANUAL'?'warning':'info'">{{({READY:'等待审核',RUNNING:'正在审核',MANUAL:'已转人工',APPLIED:'已自动处理',STALE:'内容已更新'})[automatic.state]}}</el-tag><p>{{automatic.reason}}</p><small v-if="automatic.model">模型 {{automatic.model}} · 置信度 {{Math.round((automatic.confidence||0)*100)}}%</small></template><strong>人工复核助手</strong><p>自动审核记录可在队列中查看。人工接管时，可再次分析文字作为参考；最终操作使用下方审核按钮。</p><el-button :loading="busy" :disabled="busy" @click="assist">分析内容与举报</el-button><el-alert v-if="error" :title="error" type="warning" :closable="false"/><template v-if="result"><el-tag>AI 建议：{{({REVIEW:'人工核实',APPROVE:'可考虑通过',REJECT:'建议进一步处理'})[result.decision]}}</el-tag><p class="reason">{{result.reason}}</p><ul><li v-for="(check,index) in result.checks" :key="index">{{check}}</li></ul><small>由 {{result.model}} 生成，请结合原文和图片独立判断。</small></template></section></template>
<style scoped>.assistant-review{display:flex;flex-direction:column;align-items:flex-start;gap:12px;background:var(--cl-blue-soft);padding:18px;border-radius:14px;margin:18px 0}.assistant-review p{margin:0;line-height:1.8;font-size:13px}.reason{white-space:pre-wrap}.assistant-review li{font-size:13px;line-height:1.8}</style>
