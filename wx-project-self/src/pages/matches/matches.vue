<script setup>
import LoopIcon from '../../components/LoopIcon.vue'
import { computed, ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopSegment from '../../components/LoopSegment.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import http, { TOKEN_KEY, USER_KEY, isAbortError } from '../../common/http'
import { createLatestRequestGuard } from '../../common/latest-request.mjs'
import { createExchangeJournal, creationSnapshot } from '../../common/exchange-workflow.mjs'
import { showAppModal } from '../../common/modal'

const recommendations = ref([])
const ruleVersion = ref('independent-v2')
const loading = ref(false)
const error = ref('')
const errorStatus = ref(0)
const authenticated = ref(false)
const authNotice = ref('')
const filter = ref(0)
const preview = ref(null)
const creation = ref(null), createBusy = ref(false), createError = ref('')
let alive = true, createGeneration = 0
const journal = () => createExchangeJournal(uni,Number(uni.getStorageSync(USER_KEY)?.id))
function restoreCreation() {
  try { creation.value = uni.getStorageSync(TOKEN_KEY) ? journal().creation() : null } catch { creation.value = null }
}
const openCreated = id => uni.navigateTo({url:`/pages/exchanges/exchanges?id=${id}`})
function reviewRetained() { preview.value = creation.value?.preview || null; createError.value = '' }
function restoreChangedCreation(cause) {
  restoreCreation();preview.value=creation.value?.preview || null;createError.value=cause.message
}
function discardKnownRequest() {
  try { journal().clearCreation(creation.value?.body.idempotencyKey);creation.value=null;preview.value=null;createError.value='' }
  catch(cause) {if(cause.code==='JOURNAL_CHANGED')restoreChangedCreation(cause);else createError.value=cause.message}
}
async function createExchange(retry=false) {
  if(createBusy.value) return
  const token=uni.getStorageSync(TOKEN_KEY), owner=uni.getStorageSync(USER_KEY)?.id, generation=createGeneration
  if(!token || !owner) {login();return}
  createBusy.value=true;createError.value=''
  const current=()=>alive && generation===createGeneration && token===uni.getStorageSync(TOKEN_KEY) && owner===uni.getStorageSync(USER_KEY)?.id
  let saved, savedJournal
  try {
    savedJournal=journal()
    saved=retry?savedJournal.requireCreation(creation.value?.body.idempotencyKey):savedJournal.prepare(preview.value)
    if(!saved) throw new Error('没有已保留的发起请求。')
    creation.value=saved
    if(saved.phase==='confirmed') {openCreated(saved.exchangeId);return}
    const choice=await showAppModal({title:retry?'核对并重试原发起':'正式发起交换',content:retry?'将重发已保留的同一次请求，不改变物品、需求或发起编号。':'正式发起会立即占用方案内的物品，所有参与者（包括你）仍须独立确认。请核对上方物品与流向。',confirmText:retry?'重试原请求':'正式发起'})
    if(!choice.confirm || !current()) return
    creation.value=savedJournal.updateCreation(saved.body.idempotencyKey,{phase:'uncertain'})
    const result=await http.post('/api/exchanges',saved.body,{silent:true,uncertainOnFailure:true})
    if(!current()) return
    creation.value=savedJournal.updateCreation(saved.body.idempotencyKey,{phase:'confirmed',exchangeId:result.id})
    preview.value=null;openCreated(result.id)
  } catch(cause) {
    if(!current() && !(cause.status===401 && alive && generation===createGeneration && !uni.getStorageSync(TOKEN_KEY))) return
    if(cause.status===401) {authenticated.value=false;authNotice.value='登录已过期；同一账号登录后可继续核对已保留的发起请求。';creation.value=null;preview.value=null;return}
    if(cause.code==='JOURNAL_CHANGED') {restoreChangedCreation(cause);return}
    createError.value=cause.message
    if([400,403,404,409,422].includes(cause.status)) {
      try {creation.value=savedJournal.updateCreation(saved.body.idempotencyKey,{phase:'rejected'})}
      catch(changed) {restoreChangedCreation(changed);return}
      const retained=creation.value?.preview
      await load();if(current()) preview.value=retained
    }
  } finally {if(alive && generation===createGeneration) createBusy.value=false}
}
let activeRequest
const requestGuard = createLatestRequestGuard(() => uni.getStorageSync(TOKEN_KEY))

const visible = computed(() => recommendations.value.filter(match => !filter.value || match.length === filter.value + 1))
const login = () => uni.navigateTo({url:`/pages/login/login?redirect=matches${authNotice.value ? '&reason=session-expired' : ''}`})
const openItem = id => uni.navigateTo({url:`/pages/detail/detail?id=${id}`})
const openDemands = () => uni.navigateTo({url:'/pages/demands/demands'})
const openExchanges = () => uni.navigateTo({url:'/pages/exchanges/exchanges'})
const previewMatch = match => {
  createError.value = ''
  if(creation.value && !['confirmed','rejected'].includes(creation.value.phase)) {
    preview.value=creation.value.preview
    createError.value='请先核对已保留的原方案。未确认结果前，不能改选另一方案发起。'
    return
  }
  preview.value = match
  try {creationSnapshot(match)} catch(cause) {createError.value=cause.message}
}
const closePreview = () => { preview.value = null }

async function load() {
  alive = true
  restoreCreation()
  preview.value = null
  const ticket = requestGuard.begin()
  const token = ticket.identity
  activeRequest?.abort?.()
  authenticated.value = !!token
  recommendations.value = []
  error.value = ''
  errorStatus.value = 0
  if (!authenticated.value) { loading.value = false; return }
  authNotice.value = ''
  loading.value = true
  try {
    activeRequest = http.get('/api/matches/independent',{ruleVersion:'independent-v2'},{silent:true})
    const data = await activeRequest
    if (!requestGuard.isCurrent(ticket)) return
    ruleVersion.value = data.ruleVersion
    recommendations.value = data.recommendations
  } catch (cause) {
    // The HTTP wrapper clears this session before rejecting its 401 response.
    // Still accept that failure, but never one superseded by another request/account.
    const clearedSession = cause.status === 401 && !uni.getStorageSync(TOKEN_KEY) && requestGuard.isLatest(ticket)
    if ((!requestGuard.isCurrent(ticket) && !clearedSession) || isAbortError(cause)) return
    error.value = cause.message
    errorStatus.value = cause.status || 0
    if (cause.status === 401) { authenticated.value = false; loading.value = false; authNotice.value = '登录已过期，请重新登录后读取推荐。' }
  } finally {
    if (requestGuard.isCurrent(ticket)) loading.value = false
  }
}

onShow(() => {createBusy.value=false;return load()})
onHide(() => {alive=false;preview.value=null;createGeneration++;requestGuard.invalidate();activeRequest?.abort?.()})
onUnload(() => { alive = false; createGeneration++; preview.value = null; requestGuard.invalidate(); activeRequest?.abort?.() })
</script>

<template>
  <LoopLayout active-tab="matches">
    <view class="match-heading"><view class="cl-page-heading"><text class="match-kicker">A LITTLE MATCH, A NEW LOOP</text><text class="cl-title">你的需要，可以这样相遇。</text><text class="cl-subtitle">仅展示服务端独立需求规则生成、且包含当前账号的双方或三方方案。</text></view><LoopIcon class="match-symbol" name="exchange" tone="primary" :size="64"/></view>
    <view class="match-toolbar"><LoopSegment v-model="filter" :options="['全部推荐','双方交换','三方循环']"/><LoopButton class="cl-btn" :disabled="loading || !authenticated" @click="load">↺ 更新推荐</LoopButton></view>
    <view v-if="creation" class="cl-panel retained-request" role="status"><text class="cl-section-title">{{ creation.phase==='confirmed' ? '已发起，可查看交换进度' : '有一次发起请求需要核对' }}</text><text class="cl-hint">{{ creation.phase==='uncertain' ? '上次结果尚未确认。请重试原请求或查看我的交换；刷新页面不会生成新的发起编号。' : creation.phase==='rejected' ? '服务器拒绝了上次请求，原方案已保留。重新读取推荐并核对后，再决定是否重新选择。' : '已保留本账号的原方案与发起编号。' }}</text><view class="notice-actions"><LoopButton v-if="creation.phase==='confirmed'" class="cl-btn cl-btn--primary" @click="openCreated(creation.exchangeId)">查看已发起交换</LoopButton><LoopButton v-else class="cl-btn cl-btn--primary" :disabled="createBusy" @click="reviewRetained">核对保留的请求</LoopButton><LoopButton v-if="['confirmed','rejected','prepared'].includes(creation.phase)" class="cl-btn" :disabled="createBusy" @click="discardKnownRequest">{{ creation.phase==='confirmed'?'收起提示':'已核对，重新选择' }}</LoopButton></view></view>
    <view class="cl-notice match-notice"><view><text class="cl-field-title">规则 {{ ruleVersion }}</text><text class="cl-hint">分类是硬条件；标签只参与服务端排序。页面不重算得分，浏览、预览和刷新都不会创建交换或占用物品。</text></view><view class="notice-actions"><LoopButton class="cl-btn" @click="openDemands">管理独立需求</LoopButton><LoopButton class="cl-btn" @click="openExchanges">我的交换</LoopButton></view></view>

    <view v-if="!authenticated" class="cl-panel cl-empty"><LoopIcon name="user" tone="primary" :size="36"/><text>{{ authNotice || '登录后查看与你有关的独立需求推荐' }}</text><text class="cl-hint">不会回退到公开旧推荐冒充结果。</text><LoopButton class="cl-btn cl-btn--primary" @click="login">{{ authNotice ? '重新登录' : '登录' }}</LoopButton></view>
    <view v-else-if="loading" class="cl-empty"><text class="cl-label">正在读取服务端推荐快照…</text></view>
    <view v-else-if="error" class="cl-panel cl-empty" role="alert"><text class="cl-error">{{ error }}</text><text v-if="errorStatus===422" class="cl-hint">这是候选规模超限，服务端未返回截断方案。请等待候选分区能力或缩小可交换候选范围后再试，不能视为“暂无推荐”。</text><LoopButton class="cl-btn" @click="load">重新读取</LoopButton></view>
    <view v-else-if="!recommendations.length" class="cl-panel cl-empty"><LoopIcon name="exchange" tone="primary" :size="36"/><text>当前没有独立需求交换环</text><text class="cl-hint">只有 ACTIVE 需求及其本人 AVAILABLE、未占用的关联物品会参与；不会用旧物品需求随机补位。</text></view>
    <template v-else>
      <view v-if="!visible.length" class="cl-panel cl-empty"><text>当前筛选下没有推荐</text><text class="cl-hint">切换“全部推荐”可查看其他环长，筛选不会重新请求或改变服务端方案。</text></view>
      <view v-else class="match-list">
        <view v-for="match in visible" :key="match.id" class="cl-panel match-card">
          <view class="match-card-header"><view class="cl-row"><text class="cl-tag" :class="{'cl-tag--pink':match.length===3}">{{ match.length === 2 ? '双方交换' : '三方循环' }}</text><text class="match-card-title">{{ match.length }} 位同学，各得所需</text></view><view class="score"><text>{{ match.score }}</text><text class="cl-hint">服务端得分</text></view></view>
          <view class="participant-list"><view v-for="participant in match.participants" :key="participant.userId" class="participant"><text class="cl-avatar">{{ participant.displayName.slice(0,1) }}</text><view><text class="cl-field-title">{{ participant.displayName }}</text><text class="cl-hint">提供：{{ participant.itemTitle }}</text></view></view></view>
          <view class="cl-divider"/>
          <view class="flow-list">
            <view v-for="(flow,index) in match.flows" :key="`${flow.itemId}-${flow.toUserId}`" class="flow-row">
              <view class="flow-index">{{ index+1 }}</view>
              <view class="flow-main"><view class="flow-people"><text>{{ flow.fromName }}</text><text class="flow-arrow">→</text><text>{{ flow.toName }}</text></view><LoopButton class="flow-item" @click="openItem(flow.itemId)">{{ flow.itemTitle }} ↗</LoopButton><view class="demand-proof"><text class="cl-field-title">命中需求 #{{ flow.demandId }}</text><text class="hard-rule">硬条件 · {{ flow.matchedCategoryName }}分类</text><text class="soft-rule">标签排序 · {{ flow.matchedTags.length ? flow.matchedTags.join('、') : '无共同偏好标签' }}</text><text v-if="flow.matchedDemandIds.length>1" class="cl-hint">同方向另有 {{ flow.matchedDemandIds.length-1 }} 条分类匹配需求；服务端已按规则选定本条计分。</text></view><text class="flow-reason">{{ flow.reason }}</text></view>
            </view>
          </view>
          <view class="match-explanation"><text class="cl-field-title">规则解释</text><text class="cl-subtitle">{{ match.explanation }}</text><text class="cl-hint">方案 ID：{{ match.id }} · {{ match.ruleVersion }}</text></view>
          <view class="match-pending"><text class="cl-hint">这是读取时快照，不保证物品下一刻仍可交换，也不代表任何参与者已同意。</text><LoopButton class="cl-btn cl-btn--primary" @click="previewMatch(match)">发起前预览</LoopButton></view>
        </view>
      </view>
    </template>
    <LoopSheet :model-value="!!preview" title="发起交换前确认" @update:model-value="value => { if(!value) closePreview() }">
      <view v-if="preview" class="proposal-preview">
        <view><text class="cl-section-title">{{ preview.length }} 人交换方案</text><text class="cl-hint">规则 {{ preview.ruleVersion }} · 服务端推荐快照</text></view>
        <view class="preview-participants"><view v-for="participant in preview.participants" :key="participant.userId" class="preview-person"><text class="cl-avatar">{{ participant.displayName.slice(0,1) }}</text><view><text class="cl-field-title">{{ participant.displayName }}</text><text class="cl-hint">提供：{{ participant.itemTitle }}</text></view></view></view>
        <view class="cl-divider"/>
        <view class="preview-flows"><view v-for="flow in preview.flows" :key="`${flow.itemId}-${flow.toUserId}`" class="preview-flow"><text class="preview-direction">{{ flow.fromName }} → {{ flow.toName }}</text><text class="cl-field-title">{{ flow.itemTitle }}</text><text class="cl-hint">满足需求 #{{ flow.demandId }} · {{ flow.matchedCategoryName }}</text><text class="cl-hint">{{ flow.reason }}</text></view></view>
        <view class="cl-notice"><text class="cl-field-title">请确认这是你愿意参与的方案</text><text class="cl-hint">正式发起成功才会占用物品；包括发起人在内，所有人仍须确认参加。截止由服务器决定。</text></view>
        <text v-if="createError" class="cl-error" role="alert">{{ createError }}</text>
        <LoopButton v-if="creation && !['confirmed','rejected'].includes(creation.phase)" class="cl-btn cl-btn--primary cl-btn--wide" :loading="createBusy" :disabled="createBusy" @click="createExchange(true)">核对后重试原请求</LoopButton>
        <LoopButton v-else class="cl-btn cl-btn--primary cl-btn--wide" :loading="createBusy" :disabled="createBusy || !!createError" @click="createExchange(false)">确认方案，正式发起</LoopButton>
        <LoopButton class="cl-btn cl-btn--wide" @click="closePreview">返回核对</LoopButton>
      </view>
    </LoopSheet>
  </LoopLayout>
</template>

<style scoped>
.match-heading{display:flex;align-items:center;justify-content:space-between;margin:12px 0;background:var(--cl-primary-soft);padding:30px 36px;border-radius:22px;overflow:hidden}.match-heading .cl-page-heading{margin:0}.match-kicker{display:block;font-size:10px;color:var(--cl-primary);letter-spacing:2px;margin-bottom:15px}.match-symbol{font-size:110px;line-height:1;color:var(--cl-primary);opacity:.5}.match-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:25px 0 18px}.match-notice{margin-bottom:24px;display:flex;align-items:center;justify-content:space-between;gap:18px}.match-notice>view{display:flex;flex-direction:column;gap:5px}.match-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:22px}.match-card{min-width:0}.match-card-header{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;margin-bottom:20px}.match-card-title{font-size:14px;font-weight:700}.match-card-header .cl-row{gap:8px}.score{display:flex;flex-direction:column;align-items:flex-end}.score>text:first-child{font-size:22px;font-weight:750;color:var(--cl-primary)}.participant-list{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.participant{display:flex;align-items:center;gap:8px;min-width:0}.participant>view{display:flex;flex-direction:column;min-width:0}.participant .cl-hint{overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.flow-list{display:flex;flex-direction:column;gap:20px}.flow-row{display:flex;align-items:flex-start;gap:12px}.flow-index{width:26px;height:26px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-size:11px;color:var(--cl-blue);background:var(--cl-blue-soft);flex-shrink:0;margin-top:3px}.flow-main{min-width:0;flex:1}.flow-people{display:flex;gap:13px;align-items:center;font-size:14px;font-weight:650}.flow-arrow{color:var(--cl-primary);font-size:22px}.flow-item{display:inline-block;background:transparent;color:var(--cl-blue);text-align:left;padding:6px 0;font-size:13px}.demand-proof{display:flex;flex-direction:column;gap:5px;padding:12px;margin:3px 0 8px;border-radius:12px;background:var(--cl-surface-soft)}.hard-rule,.soft-rule{font-size:12px}.hard-rule{color:var(--cl-blue)}.soft-rule{color:var(--cl-muted)}.flow-reason{display:block;font-size:11px;line-height:1.8;color:var(--cl-muted);overflow-wrap:anywhere}.match-explanation{background:var(--cl-surface-soft);padding:16px;border-radius:12px;margin-top:22px}.match-explanation .cl-subtitle,.match-explanation .cl-hint{display:block;font-size:12px;margin-top:6px;overflow-wrap:anywhere}.match-pending{display:flex;flex-direction:column;gap:12px;margin-top:18px}.match-pending .cl-btn{align-self:flex-start;font-size:12px;min-height:36px;padding:7px 12px}@media(max-width:900px){.match-list{grid-template-columns:1fr}.match-symbol{font-size:80px}}@media(max-width:550px){.match-heading{padding:25px}.match-heading .cl-title{font-size:23px}.match-heading .cl-subtitle{font-size:12px}.match-symbol{display:none}.match-toolbar,.match-notice{flex-direction:column;align-items:stretch}.match-toolbar>.cl-btn{align-self:flex-end}.match-card-header{align-items:flex-start}.match-card-header .cl-row{flex-direction:column;align-items:flex-start}.participant-list{grid-template-columns:1fr}.flow-people{flex-wrap:wrap}}
.retained-request{display:flex;flex-direction:column;gap:12px;margin-bottom:20px}.notice-actions{flex-direction:row!important;flex-wrap:wrap}.proposal-preview{display:flex;flex-direction:column;gap:20px}.proposal-preview>view:first-child{display:flex;flex-direction:column;gap:6px}.preview-participants,.preview-flows{display:flex;flex-direction:column;gap:12px}.preview-person{display:flex;align-items:center;gap:10px}.preview-person>view{display:flex;flex-direction:column;gap:3px}.preview-flow{display:flex;flex-direction:column;gap:5px;padding:14px;background:var(--cl-surface-soft);border-radius:12px}.preview-direction{font-size:14px;color:var(--cl-blue);font-weight:700}.proposal-preview .cl-notice{display:flex;flex-direction:column;gap:6px}
</style>
