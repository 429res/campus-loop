<script setup>
import { platformStorage } from '../common/platform-adapters.js'
import {ref,computed} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import LoopSheet from './LoopSheet.vue'
import LoopButton from './LoopButton.vue'
import LoopIcon from './LoopIcon.vue'
import LoopSkeleton from './LoopSkeleton.vue'
import http,{TOKEN_KEY,USER_KEY} from '../common/http'
import {createExchangeJournal} from '../common/exchange-workflow.mjs'
import {showAppModal} from '../common/modal'
const props=defineProps({item:{type:Object,required:true}})
const open=ref(false),loading=ref(false),busy=ref(false),error=ref(''),plans=ref([]),selected=ref(null),retained=ref(null),viewer=ref(null)
let generation=0,alive=true
onShow(()=>{alive=true;viewer.value=Number(uni.getStorageSync(USER_KEY)?.id)||null})
const own=computed(()=>viewer.value===props.item.ownerId)
const publish=()=>uni.switchTab({url:'/pages/publish/publish'})
const journal=()=>createExchangeJournal(platformStorage,Number(uni.getStorageSync(USER_KEY)?.id))
const offered=plan=>plan?.flows.find(flow=>flow.fromUserId===viewer.value)?.itemTitle
const received=plan=>plan?.flows.find(flow=>flow.toUserId===viewer.value)?.itemTitle
const progress=id=>uni.navigateTo({url:'/pages/exchanges/exchanges'+(id?'?id='+id:'')})
async function choose(){
 const token=uni.getStorageSync(TOKEN_KEY)
 if(!token){uni.navigateTo({url:`/pages/login/login?redirect=detail&id=${props.item.id}`});return}
 if(own.value){uni.navigateTo({url:'/pages/my-items/my-items'});return}
 open.value=true;error.value='';loading.value=true;plans.value=[];selected.value=null
 const current=++generation;viewer.value=Number(uni.getStorageSync(USER_KEY)?.id)
 try{
  retained.value=journal().creation()
  if(retained.value&&!['confirmed','rejected'].includes(retained.value.phase)){selected.value=retained.value.preview;return}
  const data=await http.get('/api/matches/independent',{ruleVersion:'independent-v2'},{silent:true})
  if(!alive||current!==generation||token!==uni.getStorageSync(TOKEN_KEY))return
  plans.value=data.recommendations.filter(plan=>plan.length===2&&plan.flows.some(flow=>flow.itemId===props.item.id&&flow.toUserId===viewer.value))
  selected.value=plans.value[0]||null
 }catch(e){if(alive&&current===generation&&token===uni.getStorageSync(TOKEN_KEY))error.value=e.message}
 finally{if(current===generation)loading.value=false}
}
function discard(){try{journal().clearCreation(retained.value.body.idempotencyKey);retained.value=null;choose()}catch(e){error.value=e.message}}
async function submit(){
 if(busy.value||!selected.value)return
 const token=uni.getStorageSync(TOKEN_KEY),current=generation
 const valid=()=>alive&&current===generation&&token===uni.getStorageSync(TOKEN_KEY)
 busy.value=true;error.value='';let saved,log
 try{
  log=journal();saved=retained.value&&!['confirmed','rejected'].includes(retained.value.phase)?log.requireCreation(retained.value.body.idempotencyKey):log.prepare(selected.value)
  retained.value=saved
  const choice=await showAppModal({title:'确认发出交换邀请',content:`你提供「${offered(saved.preview)}」，换到「${received(saved.preview)}」。发起后双方物品会暂时保留，需要你和对方确认后再约定交接。`,confirmText:'发出邀请'})
  if(!choice.confirm||!valid())return
  retained.value=log.updateCreation(saved.body.idempotencyKey,{phase:'uncertain'})
  const result=await http.post('/api/exchanges',saved.body,{silent:true,uncertainOnFailure:true})
  if(!valid())return
  retained.value=log.updateCreation(saved.body.idempotencyKey,{phase:'confirmed',exchangeId:result.id});open.value=false;progress(result.id)
 }catch(e){
  if(!valid())return
  error.value=e.message
  if(saved&&[400,403,404,409,422].includes(e.status)){
   try{retained.value=log.updateCreation(saved.body.idempotencyKey,{phase:'rejected'})}catch{retained.value=log.creation()}
  }else if(e.code==='JOURNAL_CHANGED')retained.value=journal().creation()
 }finally{if(valid())busy.value=false}
}
function leave(){alive=false;generation++;open.value=false;busy.value=false}
onHide(leave);onUnload(leave)
</script>
<template>
 <LoopButton class="cl-btn cl-btn--primary cl-btn--wide" :disabled="!own&&item.status!=='AVAILABLE'" @click="choose"><LoopIcon name="exchange" tone="white" :size="20"/>{{own?'管理我的物品':item.status==='AVAILABLE'?'与这位同学交换':'暂不可交换'}}</LoopButton>
 <LoopSheet v-model="open" title="选择我的交换物品"><view class="direct-exchange"><LoopSkeleton v-if="loading" :count="1"/><template v-else>
  <text class="cl-subtitle">想换到：{{item.title}}</text>
  <text v-if="error" class="cl-error" role="alert">{{error}}</text>
  <view v-if="retained&&!['confirmed','rejected'].includes(retained.phase)" class="cl-notice"><text>你有一份已保留的交换方案：提供「{{offered(retained.preview)}}」，换到「{{received(retained.preview)}}」。{{retained.phase==='uncertain'?'上次提交结果还未确认，可用同一请求重试。':'请继续发起或重新选择。'}}</text><LoopButton v-if="retained.phase==='prepared'" class="cl-btn" @click="discard">重新选择物品</LoopButton></view>
  <template v-else><text class="cl-hint">以下物品同时满足你和对方的需求，选一件发出邀请。</text><LoopButton v-for="plan in plans" :key="plan.flows.map(f=>f.itemId).join('-')" class="direct-option" :class="{selected:plan===selected}" :aria-pressed="plan===selected" @click="selected=plan"><LoopIcon name="box" tone="primary"/><view><text class="cl-field-title">{{offered(plan)}}</text><text class="cl-hint">你换到 {{received(plan)}}</text></view><LoopIcon v-if="plan===selected" name="check" tone="primary"/></LoopButton>
   <view v-if="!plans.length" class="cl-empty"><text>暂时没有双方都合适的物品</text><text class="cl-hint">对方想要「{{item.wantedCategoryName}}」。发布符合需求的物品、审核通过后，就可以在这里选择交换。</text><LoopButton class="cl-btn" @click="publish">发布我的物品</LoopButton></view>
  </template>
  <LoopButton v-if="retained?.phase==='rejected'" class="cl-btn" @click="discard">刷新物品，重新选择</LoopButton>
  <LoopButton v-else-if="selected" class="cl-btn cl-btn--primary" :loading="busy" @click="submit">{{retained?.phase==='uncertain'?'重试原交换邀请':'确认物品，发出邀请'}}</LoopButton>
  <LoopButton class="cl-btn cl-btn--quiet" @click="progress(retained?.phase==='confirmed'?retained.exchangeId:null)">查看我的交换</LoopButton>
 </template></view></LoopSheet>
</template>
<style scoped>
.direct-exchange{display:flex;flex-direction:column;gap:18px}.direct-option{display:flex;align-items:center;gap:14px;padding:18px;border:1px solid var(--cl-border);background:var(--cl-surface);border-radius:14px;color:var(--cl-text);text-align:left}.direct-option.selected{background:var(--cl-primary-soft);border-color:var(--cl-primary)}.direct-option>view{display:flex;flex-direction:column;gap:6px;flex:1;min-width:0}.cl-notice{display:flex;flex-direction:column;gap:12px;line-height:1.8}
</style>
