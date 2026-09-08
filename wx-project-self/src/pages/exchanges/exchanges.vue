<script setup>
import { computed, ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import { showAppModal } from '../../common/modal'
import http, { isAbortError, TOKEN_KEY, USER_KEY } from '../../common/http'

const records = ref([]), total = ref(0), page = ref(1), size = 8
const loading = ref(false), error = ref(''), detail = ref(null), detailOpen = ref(false), detailLoading = ref(false), detailError = ref('')
const disputeReason = ref(''), disputeBusy = ref(false), disputeError = ref(''), disputeUncertain = ref(false)
const sessionToken = ref(''), sessionUserId = ref(0)
let listSequence = 0, detailSequence = 0, listRequest, detailRequest
const loggedIn = computed(() => !!sessionToken.value)
const currentUserId = computed(() => sessionUserId.value)
const pages = computed(() => Math.max(1,Math.ceil(total.value / size)))
const statusLabel = value => ({PENDING_CONFIRMATION:'等待邀请确认',READY:'准备交接',COMPLETED:'已完成',CANCELLED:'已取消',EXPIRED:'已过期',DISPUTED:'争议处理中'}[value] || value)
const participantName = id => detail.value?.participants?.find(value => value.userId === id)?.displayName || `用户 #${id}`
const canDispute = computed(() => detail.value?.allowedActions?.includes('DISPUTE'))

async function load(nextPage = page.value) {
  if (!loggedIn.value) { records.value=[];total.value=0;return }
  const current = ++listSequence
  listRequest?.abort?.();loading.value=true;error.value=''
  try {
    listRequest = http.get('/api/exchanges/mine',{page:nextPage,size},{silent:true})
    const data = await listRequest
    if (current !== listSequence) return
    records.value = Array.isArray(data?.records) ? data.records : [];total.value=Number(data?.total || 0);page.value=Number(data?.page || nextPage)
    if (!records.value.length && nextPage>1) return load(nextPage-1)
  } catch(e) {
    if (current === listSequence && !isAbortError(e)) {error.value=e.message;if(e.status===401)sessionToken.value=''}
  } finally {if(current===listSequence) loading.value=false}
}
async function openDetail(id) {
  const current=++detailSequence
  detailRequest?.abort?.();detail.value=null;detailError.value='';disputeError.value='';disputeReason.value='';disputeUncertain.value=false;detailOpen.value=true;detailLoading.value=true
  try {
    detailRequest=http.get(`/api/exchanges/${id}`,{}, {silent:true});const value=await detailRequest
    if(current===detailSequence) detail.value=value
  } catch(e){if(current===detailSequence&&!isAbortError(e)){detailError.value=e.message;if(e.status===401)sessionToken.value=''}}
  finally{if(current===detailSequence) detailLoading.value=false}
}
async function refreshDetail({ recovery = false } = {}) {
  if (!detail.value?.id) return
  const id=detail.value.id,current=++detailSequence
  detailRequest?.abort?.();detailLoading.value=true;detailError.value=''
  try {
    detailRequest=http.get(`/api/exchanges/${id}`,{}, {silent:true});const value=await detailRequest
    if(current!==detailSequence)return
    detail.value=value
    if(recovery){disputeUncertain.value=false;disputeError.value=value.status==='DISPUTED'?'服务器已记录争议，请勿重复提交。':'服务器仍未显示争议；请核对网络与当前状态后再决定。'}
  }catch(e){if(current===detailSequence&&!isAbortError(e)){detailError.value=e.message;if(e.status===401)sessionToken.value=''}}
  finally{if(current===detailSequence)detailLoading.value=false}
}
async function submitDispute() {
  if(disputeBusy.value||disputeUncertain.value||!detail.value)return
  const reason=disputeReason.value.trim();disputeError.value=''
  if(!reason||reason.length>1000){disputeError.value='争议原因须为 1–1000 个字符';return}
  const answer=await showAppModal({title:'登记交接争议',content:'提交后交换进入争议状态，但不会自动退款、回滚实物或释放占用。',danger:true})
  if(!answer.confirm)return
  disputeBusy.value=true
  try{
    const value=await http.post(`/api/exchanges/${detail.value.id}/dispute`,{version:detail.value.version,reason},{silent:true,uncertainOnFailure:true})
    detail.value=value;disputeReason.value='';await load(page.value)
  }catch(e){
    disputeUncertain.value=!!e.uncertain
    disputeError.value=e.uncertain?'未收到服务器响应，争议登记结果未知。请先查询交换状态，不要更换版本重复提交。':e.status===409?'交换已被其他参与者推进或版本已变化，请刷新详情核对。':e.message
    if(e.status===409)await refreshDetail()
    if(e.status===401)uni.navigateTo({url:'/pages/login/login?redirect=exchanges'})
  }finally{disputeBusy.value=false}
}
const reportInfo = () => detail.value && uni.navigateTo({url:`/pages/governance/governance?type=EXCHANGE&id=${detail.value.id}`})
const login = () => uni.navigateTo({url:'/pages/login/login?redirect=exchanges'})
onShow(() => {sessionToken.value=uni.getStorageSync(TOKEN_KEY)||'';sessionUserId.value=Number(uni.getStorageSync(USER_KEY)?.id||0);load()})
onUnload(()=>{listSequence++;detailSequence++;listRequest?.abort?.();detailRequest?.abort?.()})
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的交换</text><text class="cl-subtitle">列表、详情与可执行动作均从服务端读取。</text></view>
    <view v-if="!loggedIn" class="cl-panel cl-empty"><text class="cl-empty-symbol">↗</text><text>登录后查看本人参与的交换</text><LoopButton class="cl-btn cl-btn--primary" @click="login">登录</LoopButton></view>
    <template v-else><view v-if="loading" class="cl-empty"><text>正在读取交换…</text></view><view v-else-if="error" class="cl-panel cl-empty" role="alert"><text class="cl-error">{{ error }}</text><LoopButton class="cl-btn" @click="load(page)">重试</LoopButton></view><view v-else-if="!records.length" class="cl-panel cl-empty"><text class="cl-empty-symbol">↻</text><text>暂无交换记录</text><text class="cl-hint">浏览推荐不会创建交换或占用物品。</text></view><view v-else class="exchange-list"><LoopButton v-for="exchange in records" :key="exchange.id" class="cl-panel exchange-card" @click="openDetail(exchange.id)"><view class="card-row"><text class="cl-section-title">交换 #{{ exchange.id }}</text><text class="cl-tag" :class="exchange.status==='DISPUTED'?'cl-tag--pink':'cl-tag--muted'">{{ statusLabel(exchange.status) }}</text></view><text class="cl-hint">{{ exchange.participants?.length || 0 }} 位参与者 · 版本 {{ exchange.version }}</text><view class="flow-summary"><text v-for="flow in exchange.flows" :key="flow.itemId" class="cl-hint">物品 #{{ flow.itemId }}：{{ exchange.participants.find(value=>value.userId===flow.fromUserId)?.displayName || flow.fromUserId }} → {{ exchange.participants.find(value=>value.userId===flow.toUserId)?.displayName || flow.toUserId }}</text></view></LoopButton></view><view v-if="total" class="pager"><LoopButton class="cl-btn" :disabled="page<=1" @click="load(page-1)">上一页</LoopButton><text class="cl-hint">第 {{ page }} / {{ pages }} 页</text><LoopButton class="cl-btn" :disabled="page>=pages" @click="load(page+1)">下一页</LoopButton></view></template>
    <LoopSheet v-model="detailOpen" title="交换详情与争议">
      <view v-if="detailLoading" class="cl-empty">正在读取最新状态…</view><view v-else-if="detailError" class="cl-empty" role="alert"><text class="cl-error">{{ detailError }}</text><LoopButton class="cl-btn" @click="refreshDetail()">重试</LoopButton></view><view v-else-if="detail" class="detail-body">
        <view class="card-row"><text class="cl-section-title">交换 #{{ detail.id }}</text><text class="cl-tag cl-tag--pink">{{ statusLabel(detail.status) }}</text></view><text class="cl-hint">版本 {{ detail.version }} · 允许动作：{{ detail.allowedActions?.join(' / ') || '无' }}</text>
        <view class="cl-divider"/><text class="cl-field-title">参与者与物品流向</text><view class="flow-list"><text v-for="flow in detail.flows" :key="flow.itemId">{{ participantName(flow.fromUserId) }} 提供物品 #{{ flow.itemId }} → {{ participantName(flow.toUserId) }}</text></view>
        <view class="participant-list"><view v-for="person in detail.participants" :key="person.userId" class="participant"><text class="cl-field-title">{{ person.displayName }}{{ person.userId===currentUserId?'（我）':'' }}</text><text class="cl-hint">邀请：{{ person.confirmationStatus }} · 交出：{{ person.handedOffAt?'已声明':'未声明' }} · 收到：{{ person.receivedAt?'已声明':'未声明' }}</text></view></view>
        <view v-if="detail.status==='DISPUTED'" class="cl-notice"><text>争议已登记：{{ detail.disputeReason }}</text><text class="cl-hint">登记时间 {{ new Date(detail.disputedAt).toLocaleString() }}。所有权、需求与占用保持服务端当前状态；管理员裁决尚未实现。</text></view>
        <form v-else-if="canDispute" class="cl-form dispute-form" @submit="submitDispute"><text class="cl-section-title">登记实物交接争议</text><text class="cl-hint">仅 READY 且已经开始交接时由服务端允许。当前 B-04 契约没有争议附件。</text><textarea v-model="disputeReason" class="cl-textarea" maxlength="1000" placeholder="说明交接中发生的具体问题" :disabled="disputeBusy || disputeUncertain"/><text v-if="disputeError" class="cl-error" role="alert">{{ disputeError }}</text><view class="action-row"><LoopButton class="cl-btn cl-btn--danger" form-type="submit" :loading="disputeBusy" :disabled="disputeBusy || disputeUncertain">{{ disputeBusy?'提交中…':'登记争议' }}</LoopButton><LoopButton v-if="disputeUncertain" class="cl-btn" @click="refreshDetail({recovery:true})">查询最新状态</LoopButton></view></form>
        <view v-else class="cl-notice">服务端当前未返回 DISPUTE 动作；前端不会自行推导或更改状态。</view>
        <LoopButton class="cl-btn" @click="reportInfo">查看举报与争议说明</LoopButton>
      </view>
    </LoopSheet>
  </LoopLayout>
</template>

<style scoped>
.exchange-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.exchange-card{display:flex;flex-direction:column;align-items:stretch;text-align:left;gap:12px;width:100%}.card-row,.pager,.action-row{display:flex;align-items:center;justify-content:space-between;gap:10px}.flow-summary,.flow-list,.participant-list,.detail-body,.participant,.cl-notice{display:flex;flex-direction:column;gap:8px}.pager{justify-content:center;margin-top:18px}.detail-body{gap:18px}.participant{padding:12px;border-radius:12px;background:var(--cl-surface-soft)}.dispute-form{padding:0}.action-row{justify-content:flex-start;flex-wrap:wrap}@media(max-width:700px){.exchange-list{grid-template-columns:1fr}}@media(max-width:430px){.action-row .cl-btn{width:100%}}
</style>
