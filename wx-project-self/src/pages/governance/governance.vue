<script setup>
import { computed, ref } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import http, { TOKEN_KEY, USER_KEY } from '../../common/http'
const targetType=ref(''), targetId=ref(0), reason=ref(''), evidence=ref([])
const records=ref([]), page=ref(1), total=ref(0), busy=ref(false), uploading=ref(false), loading=ref(false)
const error=ref(''), notice=ref(''), session=ref(''), detail=ref(null), detailOpen=ref(false), pending=ref(null)
let active=true, sequence=0, detailSequence=0
const loggedIn=computed(()=>!!session.value)
const validTarget=computed(()=>targetType.value==='ITEM' && targetId.value>0)
const token=()=>uni.getStorageSync(TOKEN_KEY)||''
const current=(saved)=>active && saved===token() && saved===session.value
const key=()=>`campus-loop.report.pending.${uni.getStorageSync(USER_KEY)?.id}`
const statusLabel=value=>({SUBMITTED:'待受理',IN_REVIEW:'处理中',RESOLVED:'已处理'}[value]||value)
function failure(cause){error.value=cause.message;if(cause.status===401){session.value='';records.value=[];detail.value=null;detailOpen.value=false;pending.value=null;reason.value='';evidence.value=[]}}
async function load(next=1){
 if(!token())return
 const saved=token(),ticket=++sequence;loading.value=true
 try{const result=await http.get('/api/reports/mine',{page:next,size:8},{silent:true});if(current(saved)&&ticket===sequence){records.value=result.records;total.value=result.total;page.value=result.page}}
 catch(cause){if(current(saved)&&ticket===sequence)failure(cause);else if(!token()&&ticket===sequence)failure(cause)}
 finally{if(ticket===sequence)loading.value=false}
}
async function openDetail(id){
 detail.value=null;detailOpen.value=true;error.value='';const saved=token(),ticket=++detailSequence
 try{const result=await http.get(`/api/reports/mine/${id}`,{},{silent:true});if(current(saved)&&ticket===detailSequence)detail.value=result}
 catch(cause){if(current(saved))failure(cause)}
}
function chooseEvidence(){
 if(busy.value||uploading.value||pending.value||evidence.value.length>=5)return
 const saved=token();uploading.value=true
 uni.chooseImage({count:1,sizeType:['compressed'],success:async result=>{
   try{if(!current(saved))return;const resultUpload=await http.uploadEvidence(result.tempFilePaths[0],{silent:true});if(current(saved))evidence.value.push(resultUpload.uploadId)}
   catch(cause){if(current(saved))failure(cause)}finally{if(current(saved))uploading.value=false}
 },fail:()=>{if(current(saved))uploading.value=false}})
}
async function submit(){
 if(busy.value||uploading.value||!token())return
 if(!pending.value && (!validTarget.value || !reason.value.trim())){error.value='请从物品详情进入，并填写举报原因';return}
 const saved=token(),storageKey=key();error.value='';busy.value=true
 try{
  if(!pending.value){
   const payload={targetType:'ITEM',targetId:targetId.value,reason:reason.value.trim(),evidenceUploadIds:[...evidence.value],idempotencyKey:`report-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`}
   uni.setStorageSync(storageKey,payload);pending.value=payload
  }
  const result=await http.post('/api/reports',pending.value,{silent:true,uncertainOnFailure:true})
  if(!current(saved))return
  uni.removeStorageSync(storageKey);pending.value=null;reason.value='';evidence.value=[];notice.value=`举报 #${result.id} 已由服务器保存`;detail.value=result;detailOpen.value=true;await load(1)
 }catch(cause){
  if(!current(saved)){if(!token())failure(cause);return}
  if([400,403,404,409,422,429].includes(cause.status)){uni.removeStorageSync(storageKey);pending.value=null;failure(cause)}
  else error.value='结果无法确认，原请求已保留。可重试相同请求，服务器会按同一编号去重。'
 }finally{if(current(saved)||!token())busy.value=false}
}
onLoad(options=>{targetType.value=options.type||'';if(/^[1-9][0-9]*$/.test(options.id||'')&&Number.isSafeInteger(Number(options.id)))targetId.value=Number(options.id)})
onShow(()=>{
 active=true;const next=token()
 if(next!==session.value){sequence++;detailSequence++;records.value=[];total.value=0;detail.value=null;detailOpen.value=false;reason.value='';evidence.value=[];pending.value=null;busy.value=false;uploading.value=false;error.value='';notice.value=''}
 session.value=next
 if(next){try{const saved=uni.getStorageSync(key());if(saved?.targetType==='ITEM'){pending.value=saved;reason.value=saved.reason;targetType.value='ITEM';targetId.value=saved.targetId;evidence.value=saved.evidenceUploadIds}}catch{};load(1)}
})
onUnload(()=>{active=false;sequence++;detailSequence++})
const login=()=>uni.navigateTo({url:'/pages/login/login'})
const exchanges=()=>uni.navigateTo({url:'/pages/exchanges/exchanges'})
</script>
<template>
 <LoopLayout>
  <view class="cl-page-heading"><text class="cl-title">举报与争议</text><text class="cl-subtitle">提交物品举报，查看平台处理结果。</text></view>
  <view v-if="!loggedIn" class="cl-panel cl-empty"><text>登录后查看本人举报</text><LoopButton class="cl-btn" @click="login">登录</LoopButton></view>
  <template v-else>
   <text v-if="error" class="cl-error" role="alert">{{error}}</text><text v-if="notice" class="cl-notice">{{notice}}</text>
   <form v-if="validTarget" class="cl-panel report-form" @submit="submit">
    <text class="cl-section-title">举报物品 #{{targetId}}</text><text class="cl-hint">举报处理不自动更改物品或交换状态。</text>
    <textarea v-model="reason" class="cl-textarea" aria-label="举报原因" maxlength="1000" placeholder="请说明具体问题，最多1000字" :disabled="busy||!!pending"/>
    <text class="cl-hint">私有图片证据 {{evidence.length}} / 5 张</text>
    <view class="actions"><LoopButton class="cl-btn" :disabled="busy||uploading||!!pending||evidence.length>=5" @click="chooseEvidence">{{uploading?'上传中…':'添加证据图片'}}</LoopButton><LoopButton v-if="evidence.length" class="cl-btn" :disabled="busy||uploading||!!pending" @click="evidence=[]">移除证据引用</LoopButton></view>
    <text v-if="pending" class="cl-notice">有一项结果待确认的提交。重试会使用完全相同的内容。</text>
    <LoopButton class="cl-btn cl-btn--primary" form-type="submit" :loading="busy" :disabled="busy||uploading">{{pending?'重试原请求':'提交举报'}}</LoopButton>
   </form>
   <view v-else class="cl-notice">从物品详情选择「举报」可填写物品举报。实物交接问题请进入「我的交换」。</view>
   <view class="cl-section-heading"><text class="cl-section-title">我的举报</text><LoopButton class="cl-btn" :disabled="loading" @click="load(page)">刷新</LoopButton></view>
   <view v-if="loading" class="cl-empty">读取中…</view><view v-else-if="!records.length" class="cl-panel cl-empty">暂无举报</view>
   <view v-for="record in records" :key="record.id" class="cl-panel report-card"><text class="cl-field-title">#{{record.id}} · {{record.targetSummary}}</text><text class="cl-tag">{{statusLabel(record.status)}}</text><text v-if="record.decisionReason">{{record.decisionReason}}</text><LoopButton class="cl-btn" @click="openDetail(record.id)">查看详情</LoopButton></view>
   <view class="actions pager"><LoopButton class="cl-btn" :disabled="loading||page<=1" @click="load(page-1)">上一页</LoopButton><text>第 {{page}} 页 · 共 {{total}} 条</text><LoopButton class="cl-btn" :disabled="loading||page*8>=total" @click="load(page+1)">下一页</LoopButton></view>
   <view class="cl-panel report-card"><text class="cl-section-title">实物交接争议</text><text class="cl-hint">登记后交换停止推进并保留占用。当前不提供管理员裁决。</text><LoopButton class="cl-btn" @click="exchanges">我的交换</LoopButton></view>
  </template>
  <LoopSheet v-model="detailOpen" title="举报详情"><view v-if="detail" class="report-card"><text class="cl-section-title">#{{detail.id}} · {{statusLabel(detail.status)}}</text><text>{{detail.targetSummary}}</text><text class="statement">{{detail.reason}}</text><text>提交时间：{{detail.createdAt}}</text><text>私有证据：{{detail.evidence.length}} 项</text><text>{{detail.decision==='UPHELD'?'举报成立':detail.decision==='DISMISSED'?'举报不成立':'尚未决定'}}</text><text class="statement">{{detail.decisionReason}}</text></view><text v-else>{{error||'读取中…'}}</text></LoopSheet>
 </LoopLayout>
</template>
<style scoped>
.report-form,.report-card{display:flex;flex-direction:column;gap:16px;margin:18px 0}.actions{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.pager{justify-content:center}.statement{white-space:pre-wrap;overflow-wrap:anywhere}.cl-textarea{width:100%;box-sizing:border-box;min-height:130px}
</style>
