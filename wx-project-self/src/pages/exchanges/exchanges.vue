<script setup>
import { platformStorage } from '../../common/platform-adapters.js'
import LoopSkeleton from '../../components/LoopSkeleton.vue'
import { computed, ref } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopPicker from '../../components/LoopPicker.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import http, { TOKEN_KEY, USER_KEY, imageUrl } from '../../common/http'
import { showAppModal } from '../../common/modal'
import { createLatestRequestGuard } from '../../common/latest-request.mjs'
import { EXCHANGE_STATUSES, STATUS_LABELS, ACTION_LABELS, createExchangeJournal, actionRequest, expiryText } from '../../common/exchange-workflow.mjs'

const authenticated=ref(false), records=ref([]), page=ref(1), total=ref(0), filter=ref(0)
const resolutions=ref([]),resolutionError=ref('')
const loading=ref(false), listError=ref(''), detail=ref(null), detailLoading=ref(false), detailError=ref('')
const itemTitles=ref({}), targetId=ref(null), now=ref(Date.now()), notice=ref('')
const editorOpen=ref(false), actionBusy=ref(false), actionError=ref(''), pending=ref(null), reason=ref(''), acknowledged=ref(false)
const PAGE_SIZE=6
const pages=computed(()=>Math.max(1,Math.ceil(total.value/PAGE_SIZE)))
const currentPerson=computed(()=>detail.value?.participants.find(p=>p.userId===Number(uni.getStorageSync(USER_KEY)?.id)))
const isHandoff=computed(()=>['HANDED_OFF','RECEIVED'].includes(pending.value?.action))
const frozen=computed(()=>pending.value && pending.value.phase!=='draft')
const actionTitle=computed(()=>ACTION_LABELS[pending.value?.action] || '核对交换操作')
const journal=()=>createExchangeJournal(platformStorage,Number(uni.getStorageSync(USER_KEY)?.id))
const token=()=>uni.getStorageSync(TOKEN_KEY)
const listGuard=createLatestRequestGuard(token), detailGuard=createLatestRequestGuard(token), actionGuard=createLatestRequestGuard(token)
let active=true, timer, lastIdentity=''
const statusLabel=value=>STATUS_LABELS[EXCHANGE_STATUSES.indexOf(value)] || value
const formatTime=value=>value ? new Date(value).toLocaleString() : '尚未提交'
const itemTitle=id=>detail.value?.flows.find(f=>f.itemId===id)?.itemTitle || itemTitles.value[id] || `物品 #${id}`
const orderTitle=exchange=>exchange.flows.map(f=>f.itemTitle||`物品 #${f.itemId}`).join(' ⇄ ')
function closeDetail(){if(actionBusy.value)return;retainDraft();detailGuard.invalidate();targetId.value=null;detail.value=null;pending.value=null;editorOpen.value=false;uni.pageScrollTo({scrollTop:0,duration:200})}
const personName=id=>detail.value?.participants.find(p=>p.userId===id)?.displayName || `同学 #${id}`
const recommendations=()=>uni.switchTab({url:'/pages/matches/matches'})
const login=()=>uni.navigateTo({url:`/pages/login/login?redirect=exchanges${targetId.value?`&exchangeId=${targetId.value}`:''}${notice.value?'&reason=session-expired':''}`})
function authFailure(cause,ticket,guard) {
  if(cause.status===401 && active && guard.isLatest(ticket) && !token()) {
    authenticated.value=false;records.value=[];detail.value=null;itemTitles.value={};pending.value=null;editorOpen.value=false
    loading.value=false;detailLoading.value=false;notice.value='登录已过期，请重新登录后继续核对交换。'
    return true
  }
  return false
}
async function loadList(next=page.value) {
  if(!token()) return
  const ticket=listGuard.begin();loading.value=true;listError.value=''
  try {
    const data=await http.get('/api/exchanges/mine',{page:next,size:PAGE_SIZE,...(filter.value?{status:EXCHANGE_STATUSES[filter.value]}:{})},{silent:true})
    if(!active || !listGuard.isCurrent(ticket)) return
    const lastPage=Math.max(1,Math.ceil(data.total/PAGE_SIZE))
    if(data.page>lastPage) {await loadList(lastPage);return}
    records.value=data.records;page.value=data.page;total.value=data.total
  } catch(cause) {if(!authFailure(cause,ticket,listGuard) && active && listGuard.isCurrent(ticket)) {records.value=[];total.value=0;listError.value=cause.message}}
  finally {if(listGuard.isCurrent(ticket)) loading.value=false}
}
function changeFilter(event) {filter.value=Number(event.detail.value);page.value=1;records.value=[];loadList(1)}
async function loadDetail(id=targetId.value) {
  if(!id || !token()) return
  targetId.value=id
  const ticket=detailGuard.begin();detailLoading.value=true;detailError.value='';resolutions.value=[];resolutionError.value=''
  try {
    const data=await http.get(`/api/exchanges/${id}`,{},{silent:true})
    if(!active || !detailGuard.isCurrent(ticket)) return
    detail.value=data
    if(data.disputeReason)http.get(`/api/exchanges/${id}/resolutions`,{},{silent:true}).then(value=>{if(active&&detailGuard.isCurrent(ticket))resolutions.value=value}).catch(()=>{if(active&&detailGuard.isCurrent(ticket))resolutionError.value='处理记录读取失败，请刷新详情'})
    if(!(pending.value?.phase==='draft' && pending.value.exchangeId===id)) {
      try {pending.value=journal().action(id)} catch {pending.value=null}
    }
    const listed=records.value.find(record=>record.id===data.id)
    if(listed && (listed.version!==data.version || listed.status!==data.status)) {
      // Reapply the server's status filter and total instead of patching a row that may no longer belong here.
      await loadList(page.value)
      if(!active || !detailGuard.isCurrent(ticket))return
    }
    const titles=await Promise.all(data.flows.map(async flow=>{
      try {const item=await http.get(`/api/items/${flow.itemId}`,{},{silent:true});return [flow.itemId,item.title]} catch {return [flow.itemId,`物品 #${flow.itemId}`]}
    }))
    if(active && detailGuard.isCurrent(ticket)) itemTitles.value={...itemTitles.value,...Object.fromEntries(titles)}
  } catch(cause) {if(!authFailure(cause,ticket,detailGuard) && active && detailGuard.isCurrent(ticket)) {detail.value=null;detailError.value=cause.message}}
  finally {if(detailGuard.isCurrent(ticket)) detailLoading.value=false}
}
function openDetail(id) {if(actionBusy.value)return;detail.value=null;pending.value=null;editorOpen.value=false;loadDetail(id);uni.pageScrollTo({scrollTop:0,duration:200})}
function restoreEditor() {
  if(!pending.value)return
  reason.value=pending.value.request.body.reason || pending.value.request.body.note || ''
  acknowledged.value=pending.value.request.body.acknowledged===true
  actionError.value='';editorOpen.value=true
}
function beginAction(action) {
  if(actionBusy.value || !detail.value?.allowedActions.includes(action))return
  if(pending.value) {restoreEditor();return}
  pending.value={action,exchangeId:detail.value.id,phase:'draft',request:{body:{version:detail.value.version}}}
  reason.value='';acknowledged.value=false;actionError.value='';editorOpen.value=true
}
function restoreChangedAction(id,cause) {
  pending.value=journal().action(id);editorOpen.value=editorOpen.value && !!pending.value
  reason.value=pending.value?.request.body.reason || pending.value?.request.body.note || ''
  acknowledged.value=pending.value?.request.body.acknowledged===true
  actionError.value=cause.message;notice.value=cause.message
}
function retainDraft() {
  if(pending.value?.phase!=='draft' || !token())return
  const body={...pending.value.request.body,...(isHandoff.value?{note:reason.value,acknowledged:acknowledged.value}:{reason:reason.value})}
  pending.value={...pending.value,request:{...pending.value.request,body}}
  try {pending.value=journal().saveAction(pending.value.exchangeId,pending.value)}
  catch(cause) {if(cause.code==='JOURNAL_CHANGED')restoreChangedAction(pending.value.exchangeId,cause)}
}
function dismissDraft() {
  if(actionBusy.value || pending.value?.phase!=='draft')return
  try {journal().clearAction(pending.value.exchangeId,pending.value.journalRevision);pending.value=null;reason.value='';acknowledged.value=false;editorOpen.value=false}
  catch(cause) {restoreChangedAction(targetId.value,cause)}
}
function closeEditor() {if(!actionBusy.value) {retainDraft();editorOpen.value=false}}
async function refreshActionDetail() {retainDraft();await loadDetail()}
async function submitAction() {
  if(actionBusy.value || !pending.value || !detail.value)return
  actionError.value=''
  const id=detail.value.id, ticket=actionGuard.begin(), savedJournal=journal()
  let saved=pending.value
  try {
    if(saved.phase==='draft') saved={...saved,exchangeId:id,request:actionRequest(saved.action,saved.request.body.version,{reason:reason.value,acknowledged:acknowledged.value})}
  } catch(cause) {actionError.value=cause.message;return}
  actionBusy.value=true
  try {
    const choice=await showAppModal({title:ACTION_LABELS[saved.action],content:saved.action==='CONFIRM'?'确认愿意按上方流向参与交换；这一步不表示已经交出或收到实物。':saved.action==='CANCEL'?'确认取消整个交换？成功后将释放本次交换的物品。':saved.action==='DISPUTE'?'登记后交换停止推进并保留物品占用，等待后续处理。':`请确认你已实际${saved.action==='HANDED_OFF'?'交出本人提供的':'收到约定的'}物品；声明提交后不能直接改写。`,confirmText:'确认提交',danger:['CANCEL','DISPUTE'].includes(saved.action)})
    if(!choice.confirm || !active || !actionGuard.isCurrent(ticket))return
    saved=savedJournal.saveAction(id,{...saved,phase:'uncertain'});pending.value=saved
    const result=await http.post(`/api/exchanges/${id}/${saved.request.path}`,saved.request.body,{silent:true,uncertainOnFailure:true})
    if(!active || !actionGuard.isCurrent(ticket))return
    savedJournal.clearAction(id,saved.journalRevision);pending.value=null;detail.value=result;editorOpen.value=false
    notice.value='服务器已确认操作结果。';await loadList();await loadDetail(id)
  } catch(cause) {
    if(authFailure(cause,ticket,actionGuard))return
    if(!active || !actionGuard.isCurrent(ticket))return
    if(cause.code==='JOURNAL_CHANGED') {restoreChangedAction(id,cause);return}
    actionError.value=cause.message
    if([400,403,404,409,422].includes(cause.status)) {
      try {pending.value=savedJournal.saveAction(id,{...saved,phase:'conflict'})}
      catch(changed) {restoreChangedAction(id,changed);return}
      await loadDetail(id)
    }
  } finally {if(active && actionGuard.isLatest(ticket))actionBusy.value=false}
}
function useCurrentVersion() {
  if(actionBusy.value || pending.value?.phase!=='conflict' || !detail.value?.allowedActions.includes(pending.value.action))return
  const saved={...pending.value,phase:'draft',request:{...pending.value.request,body:{...pending.value.request.body,version:detail.value.version}}}
  try {pending.value=journal().saveAction(detail.value.id,saved);restoreEditor()}
  catch(cause) {restoreChangedAction(detail.value.id,cause)}
}
function dismissRejected() {
  if(actionBusy.value || pending.value?.phase!=='conflict')return
  try {journal().clearAction(targetId.value,pending.value.journalRevision);pending.value=null;editorOpen.value=false}
  catch(cause) {restoreChangedAction(targetId.value,cause)}
}
async function init() {
  active=true;actionBusy.value=false;authenticated.value=!!token()
  const identity=`${token() || ''}:${uni.getStorageSync(USER_KEY)?.id || ''}`
  if(identity!==lastIdentity) {listGuard.invalidate();detailGuard.invalidate();actionGuard.invalidate();records.value=[];detail.value=null;pending.value=null;itemTitles.value={};editorOpen.value=false;actionBusy.value=false;loading.value=false;detailLoading.value=false;lastIdentity=identity}
  clearInterval(timer);now.value=Date.now()
  if(!authenticated.value)return
  notice.value='';timer=setInterval(()=>{now.value=Date.now()},30000)
  await Promise.all([loadList(page.value),targetId.value?loadDetail(targetId.value):Promise.resolve()])
}
onLoad(options=>{if(/^[1-9][0-9]*$/.test(options.id || '') && Number.isSafeInteger(Number(options.id)))targetId.value=Number(options.id)})
onShow(init)
onHide(()=>{retainDraft();editorOpen.value=false;active=false;clearInterval(timer);listGuard.invalidate();detailGuard.invalidate();actionGuard.invalidate()})
onUnload(()=>{retainDraft();active=false;clearInterval(timer);listGuard.invalidate();detailGuard.invalidate();actionGuard.invalidate()})
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的交换</text><text class="cl-subtitle">从确认参加到实物交接，每一步都由你亲自核对。</text></view>
    <view v-if="!authenticated" class="cl-panel cl-empty"><text>{{ notice || '登录后查看你的交换与邀请' }}</text><LoopButton class="cl-btn cl-btn--primary" @click="login">登录并继续</LoopButton></view>
    <template v-else>
      <template v-if="!targetId"><view class="exchange-toolbar"><view class="status-picker"><LoopPicker :range="STATUS_LABELS" :value="filter" aria-label="筛选交换状态" :disabled="loading" @change="changeFilter"><view class="cl-input">{{ STATUS_LABELS[filter] }}</view></LoopPicker></view><LoopButton class="cl-btn" :disabled="loading" @click="loadList()">刷新列表</LoopButton><LoopButton class="cl-btn" @click="recommendations">查看推荐</LoopButton></view>
      <text v-if="notice" class="cl-notice" role="status">{{ notice }}</text>
      <LoopSkeleton v-if="loading" />
      <view v-else-if="listError" class="cl-panel cl-empty" role="alert"><text class="cl-error">{{ listError }}</text><LoopButton class="cl-btn" @click="loadList()">重试读取</LoopButton></view>
      <view v-else-if="!records.length" class="cl-panel cl-empty">当前筛选下暂无交换。</view>
      <view v-else class="exchange-list"><view v-for="exchange in records" :key="exchange.id" class="cl-panel exchange-card"><view class="cl-row"><text class="cl-section-title order-title">{{orderTitle(exchange)}}</text><text class="cl-tag">{{ statusLabel(exchange.status) }}</text></view><view class="order-images"><view v-for="flow in exchange.flows" :key="flow.itemId"><image :src="imageUrl(flow.imageUrl,flow.itemTitle)" mode="aspectFill"/><text>{{flow.itemTitle||`物品 #${flow.itemId}`}}</text></view></view><text class="cl-hint">{{ exchange.participants.map(p=>p.displayName).join(' · ') }} · {{ exchange.participants.length }} 人</text><text class="cl-hint">发起于 {{ formatTime(exchange.createdAt) }}</text><text class="cl-hint">{{ expiryText(exchange,now) }}</text><LoopButton class="cl-btn cl-btn--primary" @click="openDetail(exchange.id)">查看订单详情</LoopButton></view></view>
      <view class="exchange-pagination"><LoopButton class="cl-btn" :disabled="loading || page<=1" @click="loadList(page-1)">上一页</LoopButton><text class="cl-hint">第 {{ page }} / {{ pages }} 页 · 共 {{ total }} 条</text><LoopButton class="cl-btn" :disabled="loading || page>=pages" @click="loadList(page+1)">下一页</LoopButton></view>
      </template><view v-if="targetId" class="cl-panel exchange-detail">
        <view class="detail-heading"><LoopButton class="cl-btn" :disabled="actionBusy" @click="closeDetail">‹ 返回订单列表</LoopButton><text class="cl-section-title">{{detail?orderTitle(detail):'交换详情'}}</text><LoopButton class="cl-btn" :disabled="detailLoading || actionBusy" @click="loadDetail()">刷新详情</LoopButton></view>
        <LoopSkeleton v-if="detailLoading&&!detail" :count="1"/>
        <text v-if="detailError" class="cl-error" role="alert">{{ detailError }}</text>
        <template v-if="detail">
          <view v-if="detail.ruleVersion==='direct-v1'" class="cl-notice">自主交换：请核对实际物品，需求仅供参考，确认后再安排交接。</view><view class="cl-row"><text class="cl-tag cl-tag--pink">{{ statusLabel(detail.status) }}</text><text class="cl-hint">记录版本 {{ detail.version }}</text></view>
          <text class="cl-hint">原确认截止：{{ formatTime(detail.expiresAt) }}</text><text class="cl-notice">{{ expiryText(detail,now) || '请以下方已记录的结果为准。' }}</text>
          <view class="detail-flows"><view v-for="flow in detail.flows" :key="flow.itemId" class="detail-flow"><text class="cl-field-title">{{ personName(flow.fromUserId) }} → {{ personName(flow.toUserId) }}</text><image class="detail-item-image" :src="imageUrl(flow.imageUrl,flow.itemTitle)" mode="aspectFill"/><text>{{ itemTitle(flow.itemId) }}</text></view></view>
          <view v-for="person in detail.participants" :key="person.userId" class="participant-progress"><text class="cl-field-title">{{ person.displayName }}{{ person.userId===currentPerson?.userId?'（你）':'' }}</text><text class="cl-hint">确认参加：{{ formatTime(person.confirmedAt) }}</text><text class="cl-hint">交出 {{ itemTitle(person.offeredItemId) }}：{{ formatTime(person.handedOffAt) }}</text><text v-if="person.handedOffNote" class="cl-hint">交出说明：{{ person.handedOffNote }}</text><text class="cl-hint">收到 {{ itemTitle(person.receivedItemId) }}：{{ formatTime(person.receivedAt) }}</text><text v-if="person.receivedNote" class="cl-hint">收到说明：{{ person.receivedNote }}</text></view>
          <view v-if="detail.cancellationReason" class="cl-notice"><text>取消原因：{{ detail.cancellationReason }} · {{ formatTime(detail.cancelledAt) }}</text></view>
          <view v-if="detail.disputeReason" class="cl-notice"><text>争议原因：{{ detail.disputeReason }} · {{ formatTime(detail.disputedAt) }}</text><text v-if="detail.status==='DISPUTED'" class="cl-hint">正在等待管理员处理，结果会通过站内消息通知。</text></view>
          <text v-if="resolutionError" class="cl-error">{{resolutionError}}</text><view v-for="record in resolutions" :key="record.id" class="cl-notice"><text>{{record.decision==='RESUME'?'已恢复交接':'已终止交换'}} · {{formatTime(record.createdAt)}}</text><text>{{record.reason}}</text></view>
          <view v-if="pending" class="cl-notice pending-action"><text class="cl-field-title">有一项 {{ ACTION_LABELS[pending.action] }} 需要核对</text><text class="cl-hint">原请求版本 {{ pending.request.body.version }}；{{ pending.phase==='uncertain'?'结果未知，可重试完全相同的请求。':'原说明和版本已保留，请结合最新详情决定。' }}</text><LoopButton class="cl-btn" :disabled="actionBusy" @click="restoreEditor">核对保留的操作</LoopButton></view>
          <view class="exchange-actions"><LoopButton v-for="action in detail.allowedActions" :key="action" class="cl-btn" :class="{'cl-btn--primary':['CONFIRM','HANDED_OFF','RECEIVED'].includes(action)}" :disabled="actionBusy || detailLoading || !!pending" @click="beginAction(action)">{{ ACTION_LABELS[action] }}</LoopButton></view>
          <text v-if="!detail.allowedActions.length" class="cl-hint">服务器当前没有可执行操作，可刷新核对进度。</text>
        </template>
      </view>
    </template>
    <LoopSheet :model-value="editorOpen" :title="actionTitle" @update:model-value="value=>{if(!value)closeEditor()}">
      <view v-if="pending" class="action-form">
        <text class="cl-hint">交换 #{{ targetId }} · 本次使用版本 {{ pending.request.body.version }}</text>
        <text v-if="isHandoff" class="cl-notice">{{ pending.action==='HANDED_OFF'?'确认你已经交出：':'确认你已经收到：' }}{{ itemTitle(pending.action==='HANDED_OFF'?currentPerson?.offeredItemId:currentPerson?.receivedItemId) }}</text>
        <view v-if="pending.action!=='CONFIRM'" class="cl-field"><text class="cl-field-title">{{ isHandoff?'交接说明（可选）':'原因（必填）' }}</text><textarea v-model="reason" class="cl-textarea" :aria-label="isHandoff?'交接说明':'操作原因'" :disabled="actionBusy || frozen" maxlength="1000" :placeholder="isHandoff?'可记录当面交接情况':'请说明原因，最多1000字'"/></view>
        <LoopButton v-if="isHandoff" class="cl-btn acknowledge-button" :aria-pressed="acknowledged" :disabled="actionBusy || frozen" @click="acknowledged=!acknowledged">{{ acknowledged?'☑':'□' }} 我确认已实际{{ pending.action==='HANDED_OFF'?'交出':'收到' }}上述物品</LoopButton>
        <text v-if="actionError" class="cl-error" role="alert">{{ actionError }}</text>
        <text v-if="frozen" class="cl-hint">原请求内容已锁定。重试只提交相同的声明、说明和版本。</text>
        <LoopButton class="cl-btn cl-btn--primary" :loading="actionBusy" :disabled="actionBusy || detailLoading || !detail" @click="submitAction">{{ frozen?'重试原请求':'核对并提交' }}</LoopButton>
        <template v-if="pending.phase==='conflict'"><text class="cl-hint">服务器当前版本 {{ detail?.version ?? '读取失败' }}。确认最新状态后，可沿用原说明重新决定。</text><LoopButton v-if="detail?.allowedActions.includes(pending.action)" class="cl-btn" :disabled="actionBusy || detailLoading" @click="useCurrentVersion">已核对，使用当前版本重新填写</LoopButton><LoopButton class="cl-btn" :disabled="actionBusy" @click="dismissRejected">已核对，收起被拒绝的操作</LoopButton></template>
        <LoopButton class="cl-btn" :disabled="actionBusy" @click="refreshActionDetail">重新读取服务器详情</LoopButton>
        <LoopButton v-if="pending.phase==='draft'" class="cl-btn" :disabled="actionBusy" @click="dismissDraft">取消本次填写</LoopButton>
      </view>
    </LoopSheet>
  </LoopLayout>
</template>

<style scoped>
.exchange-toolbar,.exchange-pagination,.detail-heading,.exchange-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.exchange-toolbar{margin-bottom:20px}.status-picker{min-width:160px;max-width:250px;flex:1}.exchange-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px;margin-top:18px}.exchange-card,.exchange-detail,.action-form,.pending-action{display:flex;flex-direction:column;gap:14px;min-width:0}.exchange-card .cl-row{justify-content:space-between}.exchange-pagination{justify-content:center;margin:24px 0}.exchange-detail{margin:28px 0}.detail-heading{justify-content:space-between}.detail-flows{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.detail-flow,.participant-progress{display:flex;flex-direction:column;gap:6px;padding:16px;border:1px solid var(--cl-border);border-radius:14px;background:var(--cl-surface-soft);overflow-wrap:anywhere}.detail-flow{color:var(--cl-blue)}.participant-progress{text-align:left}.acknowledge-button{text-align:left;white-space:normal}.acknowledge-button[aria-pressed=true]{background:var(--cl-primary-soft);border-color:var(--cl-primary)}.action-form .cl-textarea{width:100%;box-sizing:border-box;min-height:120px}.exchange-detail>.cl-notice{display:flex;flex-direction:column;gap:6px}.exchange-actions .cl-btn{flex:1;min-width:120px}@media(max-width:700px){.exchange-list,.detail-flows{grid-template-columns:1fr}.exchange-card,.exchange-detail{padding:20px}.exchange-pagination{gap:8px}}
.order-title{line-height:1.5;overflow-wrap:anywhere}.order-images{display:flex;gap:12px}.order-images>view{flex:1;min-width:0;display:flex;flex-direction:column;gap:8px}.order-images image{width:100%;height:140px;border-radius:14px}.order-images text{font-size:12px;line-height:1.5;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.detail-item-image{width:100%;height:170px;border-radius:12px}.detail-heading .cl-section-title{flex:1;min-width:180px;overflow-wrap:anywhere}.exchange-detail{margin-top:0}
</style>
