<script setup>
import {ref,onBeforeUnmount} from 'vue'
import LoopButton from './LoopButton.vue'
import LoopSheet from './LoopSheet.vue'
import LoopInput from './LoopInput.vue'
import http,{TOKEN_KEY} from '../common/http'
const props=defineProps({description:String,disabled:Boolean});const emit=defineEmits(['apply'])
const open=ref(false),input=ref(''),busy=ref(false),result=ref(null),error=ref('');let resultToken='',alive=true
onBeforeUnmount(()=>{alive=false})
function start(){input.value=props.description||'';result.value=null;error.value='';open.value=true}
async function generate(){if(!input.value.trim()||busy.value)return;const token=uni.getStorageSync(TOKEN_KEY);busy.value=true;error.value='';try{const data=await http.post('/api/assistant/item-draft',{description:input.value.trim()},{silent:true,timeout:120000});if(alive&&token===uni.getStorageSync(TOKEN_KEY)){result.value=data;resultToken=token}}catch(e){if(alive&&token===uni.getStorageSync(TOKEN_KEY))error.value=e.message}finally{busy.value=false}}
function apply(){if(props.disabled||resultToken!==uni.getStorageSync(TOKEN_KEY))return;emit('apply',result.value);open.value=false}
</script>
<template><LoopButton class="cl-btn" :disabled="disabled" @click="start">✦ 千问帮我整理物品</LoopButton><LoopSheet v-model="open" dialog title="千问发布助手"><view class="cl-stack"><text class="cl-hint">写下物品名称、使用情况和瑕疵。内容会发送至千问生成草稿，你确认后再填入发布表单。</text><LoopInput v-model="input" multiline class="cl-textarea" aria-label="告诉千问我的物品" maxlength="2500" :disabled="busy"/><LoopButton class="cl-btn cl-btn--primary" :disabled="busy||!input.trim()" :loading="busy" @click="generate">{{busy?'正在整理…':'生成草稿'}}</LoopButton><text v-if="error" class="cl-error" role="alert">{{error}}</text><template v-if="result"><text class="cl-field-title">确认草稿内容</text><LoopInput v-model="result.title" class="cl-input" aria-label="助手生成标题" maxlength="100"/><LoopInput v-model="result.description" multiline class="cl-textarea" aria-label="助手生成描述" maxlength="2000"/><text class="cl-hint">{{result.tags?.join(' · ')}}</text><text v-for="(check,index) in result.checks" :key="index" class="cl-hint">待核实：{{check}}</text><LoopButton class="cl-btn cl-btn--primary" :disabled="disabled" @click="apply">确认并填入表单</LoopButton></template></view></LoopSheet></template>
