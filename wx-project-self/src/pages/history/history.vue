<script setup>
import LoopSkeleton from '../../components/LoopSkeleton.vue'
import { computed, ref } from 'vue'
import { onLoad, onShow, onPullDownRefresh, onUnload } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopPicker from '../../components/LoopPicker.vue'
import LoopSheet from '../../components/LoopSheet.vue'
import LoopSwitch from '../../components/LoopSwitch.vue'
import { showAppModal } from '../../common/modal'
import http, { isAbortError, TOKEN_KEY } from '../../common/http'

const login = () => uni.navigateTo({url:'/pages/login/login'})
const records = ref([]), total = ref(0), page = ref(1), size = 8
const loading = ref(true), error = ref(''), actionError = ref(''), actionBusy = ref('')
const formOpen = ref(false), submitting = ref(false), submitError = ref(''), uncertain = ref(false), recoveryNotice = ref('')
const types = [{label:'维修或保养',value:'REPAIR'},{label:'流转补充说明',value:'TRANSFER'}]
const emptyForm = () => ({eventType:'REPAIR',statement:'',timeUnknown:true,occurredDate:'',occurredTime:'12:00',relatedExchangeId:'',correctsEventId:null})
const form = ref(emptyForm()), evidence = ref([])
const sessionToken = ref('')
let itemId = '', itemTitle = '物品', sequence = 0, activeRequest
const hasToken = computed(() => !!sessionToken.value)
const pages = computed(() => Math.max(1,Math.ceil(total.value / size)))
const typeLabel = value => ({REPAIR:'维修/保养',TRANSFER:'流转说明',EXCHANGED:'完成交换'}[value] || value)
const level = value => ({SELF_REPORTED:['本人自述','cl-tag--muted'],BOTH_CONFIRMED:['全体参与者确认','cl-tag--pink'],ADMIN_VERIFIED:['管理员核验','']}[value] || [value,'cl-tag--muted'])
const confirmationLabel = value => ({NOT_REQUESTED:'尚未发起确认',PENDING:'等待参与者确认',COMPLETE:'确认完成',SUPERSEDED:'已有修正版本',WITHDRAWN:'确认请求已撤回',UNAVAILABLE:'旧记录无完整确认依据'}[value] || value)
const timeText = value => value ? new Date(value).toLocaleString() : '时间不详'

async function load(nextPage = page.value, { recovery = false } = {}) {
  const current = ++sequence
  activeRequest?.abort?.(); loading.value = true; error.value = ''
  try {
    activeRequest = http.get(`/api/items/${itemId}/history`,{page:nextPage,size},{silent:true})
    const data = await activeRequest
    if (current !== sequence) return
    records.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total || 0); page.value = Number(data?.page || nextPage)
    if (!records.value.length && nextPage > 1) return load(nextPage - 1,{recovery})
    if (recovery) { uncertain.value = false; recoveryNotice.value = '已重新读取服务器履历。请核对最新记录后再决定是否重新提交。' }
  } catch (e) {
    if (current === sequence && !isAbortError(e)) { error.value = e.message;if(e.status===401)sessionToken.value='' }
  } finally { if (current === sequence) loading.value = false }
}

function openCreate(corrects = null) {
  if (!hasToken.value) { uni.navigateTo({url:'/pages/login/login'}); return }
  form.value = {...emptyForm(),correctsEventId:corrects?.id || null,eventType:corrects?.eventType === 'TRANSFER' ? 'TRANSFER' : 'REPAIR'}
  evidence.value = []; submitError.value = ''; uncertain.value = false; recoveryNotice.value = ''; formOpen.value = true
}
function pickEvidence() {
  if (submitting.value || evidence.value.length >= 5) return
  uni.chooseImage({count:5-evidence.value.length,sizeType:['compressed'],sourceType:['album','camera'],success(result) {
    const additions = (result.tempFilePaths || []).map(filePath => ({filePath,uploadId:'',uploading:false,error:'',request:null}))
    evidence.value.push(...additions); additions.forEach(uploadEvidence)
  }})
}
async function uploadEvidence(entry) {
  if (entry.uploading || submitting.value) return
  entry.uploading = true; entry.error = ''
  try {
    entry.request = http.uploadEvidence(entry.filePath,{silent:true})
    const result = await entry.request
    if (evidence.value.includes(entry)) entry.uploadId = result.uploadId
  } catch (e) { if (!isAbortError(e) && evidence.value.includes(entry)) entry.error = e.message }
  finally { entry.uploading = false; entry.request = null }
}
function cancelEvidence(entry) { entry.request?.abort?.(); entry.uploading = false; entry.error = '上传已取消，可重试。' }
function removeEvidence(entry) {
  entry.request?.abort?.(); evidence.value = evidence.value.filter(value => value !== entry)
}
function occurredAt() {
  if (form.value.timeUnknown) return null
  if (!form.value.occurredDate || !/^\d{2}:\d{2}$/.test(form.value.occurredTime)) return undefined
  const value = new Date(`${form.value.occurredDate}T${form.value.occurredTime}:00`)
  if (Number.isNaN(value.getTime())) return undefined
  return value.toISOString().replace('.000Z','Z')
}
async function submit() {
  if (submitting.value || uncertain.value) return
  submitError.value = ''; recoveryNotice.value = ''
  const statement = form.value.statement.trim(), time = occurredAt()
  if (!statement || statement.length > 2000) { submitError.value = '自述内容须为 1–2000 个字符'; return }
  if (!form.value.timeUnknown && !time) { submitError.value = '请选择完整的发生日期和时间'; return }
  if (evidence.value.some(value => value.uploading || !value.uploadId)) { submitError.value = '请等待证据上传完成，失败项目可重试或移除'; return }
  const related = form.value.relatedExchangeId.trim()
  if (related && !/^[1-9][0-9]*$/.test(related)) { submitError.value = '关联交换 ID 须为正整数'; return }
  submitting.value = true
  try {
    await http.post(`/api/items/${itemId}/history`,{
      eventType:form.value.eventType,statement,occurredAt:time,timeUnknown:form.value.timeUnknown,
      relatedExchangeId:related ? Number(related) : null,correctsEventId:form.value.correctsEventId,
      evidenceUploadIds:evidence.value.map(value => value.uploadId),
    },{silent:true,uncertainOnFailure:true})
    formOpen.value = false; form.value = emptyForm(); evidence.value = []; await load(1)
    uni.showToast({title:'自述已记录',icon:'success'})
  } catch (e) {
    submitError.value = e.uncertain ? '未收到服务器响应，结果无法确认。请先查询最新履历，不要直接再次提交。' : e.message
    uncertain.value = !!e.uncertain
    if (e.status === 401) uni.navigateTo({url:'/pages/login/login'})
  } finally { submitting.value = false }
}
async function confirmationAction(event, action) {
  if (actionBusy.value) return
  actionError.value = ''
  let path, body
  if (action === 'REQUEST_CONFIRMATION') {
    const answer = await showAppModal({title:'请求全体参与者确认',content:'将把此事件及证据快照授权给该次交换的全部参与者；作者也需要独立确认。'})
    if (!answer.confirm) return
    path = 'confirmation-request'; body = {shareEvidenceWithAllParticipants:true}
  } else if (action === 'CONFIRM') {
    const answer = await showAppModal({title:'确认这条自述',content:'我认可当前事件声明及证据版本。这不是管理员核验，也不表示平台证明维修质量。'})
    if (!answer.confirm) return
    path = 'confirm'; body = {snapshotHash:event.confirmation?.snapshotHash,acknowledged:true}
  } else return
  actionBusy.value = `${event.id}:${action}`
  try {
    await http.post(`/api/items/${itemId}/history/${event.id}/${path}`,body,{silent:true,uncertainOnFailure:true})
    await load(page.value)
  } catch (e) {
    actionError.value = e.uncertain ? '确认结果无法确定。请先刷新该事件；如仍显示同一快照，可按契约重试。' : e.status === 409 ? '内容或确认状态已变化，已重新读取，请核对后再操作。' : e.message
    if (e.status === 409 || e.uncertain) await load(page.value)
  } finally { actionBusy.value = '' }
}
async function withdraw(event) {
  const reason = (event._withdrawReason || '').trim()
  if (!reason || reason.length > 500 || actionBusy.value) { actionError.value = '撤回原因须为 1–500 个字符'; return }
  const answer = await showAppModal({title:'撤回确认请求',content:'撤回不会删除原事件、既有确认或已授权的历史快照。',danger:true})
  if (!answer.confirm) return
  actionBusy.value = `${event.id}:WITHDRAW_CONFIRMATION`; actionError.value = ''
  try {
    await http.post(`/api/items/${itemId}/history/${event.id}/withdraw-confirmation`,{snapshotHash:event.confirmation.snapshotHash,reason},{silent:true,uncertainOnFailure:true})
    await load(page.value)
  } catch (e) {
    actionError.value = e.uncertain ? '撤回结果无法确定，已重新读取当前状态；不会自动再次提交。' : e.message
    if (e.status === 409 || e.uncertain) await load(page.value)
  } finally { actionBusy.value = '' }
}

onLoad(options => { itemId = String(options.id || ''); itemTitle = decodeURIComponent(options.title || '物品'); if (/^[1-9][0-9]*$/.test(itemId)) load(1); else {loading.value=false;error.value='物品链接无效'} })
onShow(() => { sessionToken.value = uni.getStorageSync(TOKEN_KEY) || '' })
onPullDownRefresh(async () => { await load(page.value);uni.stopPullDownRefresh() })
onUnload(() => { sequence++;activeRequest?.abort?.();evidence.value.forEach(value => value.request?.abort?.()) })
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">{{ itemTitle }} · 履历</text><text class="cl-subtitle">原始记录保留；来源等级只由服务端事实决定。</text></view>
    <view class="source-guide cl-panel"><view v-for="entry in [['SELF_REPORTED','本人自述','作者提交，尚未得到全体参与者或管理员核验'],['BOTH_CONFIRMED','全体参与者确认','两方须 2/2、三方须 3/3；不等同平台核验'],['ADMIN_VERIFIED','管理员核验','仅表示该事件在服务端记录的范围内通过核验']]" :key="entry[0]" class="source-row"><text class="cl-tag" :class="level(entry[0])[1]">{{ entry[1] }}</text><text class="cl-hint">{{ entry[2] }}</text></view></view>
    <view class="toolbar"><LoopButton class="cl-btn cl-btn--primary" @click="openCreate()">提交本人自述</LoopButton><LoopButton class="cl-btn" :disabled="loading" @click="load(page)">刷新履历</LoopButton></view>
    <text v-if="recoveryNotice" class="cl-notice" role="status">{{ recoveryNotice }}</text><text v-if="actionError" class="cl-error block" role="alert">{{ actionError }}</text>
    <LoopSkeleton v-if="loading"/>
    <view v-else-if="error" class="cl-panel cl-empty" role="alert"><text class="cl-error">{{ error }}</text><LoopButton class="cl-btn" @click="load(page)">重试</LoopButton><LoopButton v-if="!hasToken" class="cl-btn cl-btn--primary" @click="login">重新登录</LoopButton></view>
    <view v-else-if="!records.length" class="cl-panel cl-empty"><text class="cl-empty-symbol">○</text><text>还没有可见履历</text><text class="cl-hint">不会生成随机或占位事件。</text></view>
    <view v-else class="timeline">
      <view v-for="event in records" :key="event.id" class="cl-panel event-card">
        <view class="event-heading"><view class="event-tags"><text class="cl-tag">{{ typeLabel(event.eventType) }}</text><text class="cl-tag" :class="level(event.evidenceLevel)[1]">{{ level(event.evidenceLevel)[0] }}</text></view><text class="cl-hint">#{{ event.id }}</text></view>
        <text class="event-statement">{{ event.statement }}</text>
        <view class="event-meta"><text>作者：{{ event.authorDisplayName || '已隐藏' }}</text><text>发生：{{ event.timeUnknown ? '时间不详' : timeText(event.occurredAt) }}</text><text>记录：{{ timeText(event.recordedAt) }}</text></view>
        <view v-if="event.correctsEventId || event.correctedByEventId" class="cl-notice correction"><text v-if="event.correctsEventId">本记录修正事件 #{{ event.correctsEventId }}</text><text v-if="event.correctedByEventId">已有新修正 #{{ event.correctedByEventId }}，原文继续保留</text></view>
        <view v-if="event.confirmation" class="confirmation"><text class="cl-field-title">{{ confirmationLabel(event.confirmation.status) }}</text><text class="cl-hint">{{ event.confirmation.requiredCount ? `已确认 ${event.confirmation.confirmedCount}/${event.confirmation.requiredCount} 人` : '未建立参与者确认集合' }}</text><view v-if="event.confirmation.participants" class="participant-list"><text v-for="person in event.confirmation.participants" :key="person.userId" class="cl-hint">{{ person.displayName }} · {{ person.confirmedAt ? '已确认' : '待确认' }}</text></view></view>
        <text v-if="event.verification?.decision" class="cl-hint verification">管理员结论：{{ event.verification.decision === 'APPROVED' ? '已核验' : '未通过核验' }} · {{ timeText(event.verification.decidedAt) }}；仅适用于此事件。</text>
        <text v-if="event.evidence" class="cl-hint">私有证据 {{ event.evidence.length }} 项，仅授权身份可读取；页面不公开文件地址。</text>
        <view class="event-actions"><LoopButton v-if="event.canCorrect" class="cl-btn" @click="openCreate(event)">追加修正</LoopButton><LoopButton v-if="event.confirmation?.allowedActions?.includes('REQUEST_CONFIRMATION')" class="cl-btn" :disabled="!!actionBusy" @click="confirmationAction(event,'REQUEST_CONFIRMATION')">请求全员确认</LoopButton><LoopButton v-if="event.confirmation?.allowedActions?.includes('CONFIRM')" class="cl-btn cl-btn--primary" :disabled="!!actionBusy" @click="confirmationAction(event,'CONFIRM')">我认可此声明及证据</LoopButton></view>
        <view v-if="event.confirmation?.allowedActions?.includes('WITHDRAW_CONFIRMATION')" class="withdraw"><input v-model="event._withdrawReason" class="cl-input" maxlength="500" placeholder="撤回原因（不会删除旧记录）" :disabled="!!actionBusy"/><LoopButton class="cl-btn cl-btn--danger" :disabled="!!actionBusy" @click="withdraw(event)">撤回请求</LoopButton></view>
      </view>
    </view>
    <view v-if="!loading && !error && total" class="pager"><LoopButton class="cl-btn" :disabled="page<=1" @click="load(page-1)">上一页</LoopButton><text class="cl-hint">第 {{ page }} / {{ pages }} 页 · {{ total }} 条</text><LoopButton class="cl-btn" :disabled="page>=pages" @click="load(page+1)">下一页</LoopButton></view>
    <LoopSheet v-model="formOpen" :title="form.correctsEventId ? `修正事件 #${form.correctsEventId}` : '提交本人自述'">
      <form class="cl-form history-form" @submit="submit">
        <view class="cl-notice">只能提交本人有权描述的 REPAIR/TRANSFER 事件；来源固定为 SELF_REPORTED。修正会新增事件并保留原文。</view>
        <view class="cl-field"><text class="cl-field-title">事件类型</text><LoopPicker :range="types" range-key="label" :value="types.findIndex(value=>value.value===form.eventType)" :disabled="submitting" @change="form.eventType=types[Number($event.detail.value)].value"><view class="cl-picker"><text>{{ types.find(value=>value.value===form.eventType)?.label }}</text><text>⌄</text></view></LoopPicker></view>
        <view class="cl-field"><text class="cl-field-title">自述内容 *</text><textarea v-model="form.statement" class="cl-textarea" maxlength="2000" placeholder="只填写可公开的事实说明，不要留下联系方式或口令" :disabled="submitting"/></view>
        <view class="switch-row"><view><text class="cl-field-title">发生时间不详</text><text class="cl-hint">不以记录时间冒充发生时间</text></view><LoopSwitch :checked="form.timeUnknown" :disabled="submitting" @change="form.timeUnknown=$event.detail.value"/></view>
        <view v-if="!form.timeUnknown" class="date-grid"><view class="cl-field"><text class="cl-field-title">发生日期</text><LoopPicker mode="date" :value="form.occurredDate" :disabled="submitting" @change="form.occurredDate=$event.detail.value"><view class="cl-picker"><text>{{ form.occurredDate || '选择日期' }}</text></view></LoopPicker></view><view class="cl-field"><text class="cl-field-title">发生时间</text><LoopPicker mode="time" :value="form.occurredTime" :disabled="submitting" @change="form.occurredTime=$event.detail.value"><view class="cl-picker"><text>{{ form.occurredTime }}</text></view></LoopPicker></view></view>
        <view class="cl-field"><text class="cl-field-title">关联已完成交换 ID（如适用）</text><input v-model="form.relatedExchangeId" class="cl-input" type="number" placeholder="曾经所有者提交时按服务端要求填写" :disabled="submitting"/></view>
        <view class="cl-field"><text class="cl-field-title">私有证据（最多 5 张）</text><text class="cl-hint">证据不会进入公开图片目录；移除仅取消本表单引用，不宣称删除服务端文件。</text><LoopButton class="cl-btn" :disabled="submitting || evidence.length>=5" @click="pickEvidence">选择图片</LoopButton></view>
        <view v-for="(entry,index) in evidence" :key="entry.filePath" class="evidence-row"><image :src="entry.filePath" mode="aspectFill"/><view><text class="cl-field-title">证据 {{ index+1 }}</text><text class="cl-hint">{{ entry.uploading ? '上传中…' : entry.uploadId ? '私有上传已就绪' : entry.error || '等待上传' }}</text></view><LoopButton v-if="entry.uploading" class="cl-btn" @click="cancelEvidence(entry)">取消</LoopButton><LoopButton v-else-if="!entry.uploadId" class="cl-btn" @click="uploadEvidence(entry)">重试</LoopButton><LoopButton class="cl-btn" :disabled="submitting" @click="removeEvidence(entry)">移除引用</LoopButton></view>
        <text v-if="submitError" class="cl-error" role="alert">{{ submitError }}</text>
        <view v-if="uncertain" class="cl-notice"><text>写入结果不确定，表单与证据引用仍保留。</text><LoopButton class="cl-btn" @click="load(1,{recovery:true})">查询最新履历</LoopButton></view>
        <LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :loading="submitting" :disabled="submitting || uncertain || evidence.some(value=>value.uploading)">{{ submitting ? '提交中…' : '提交自述' }}</LoopButton>
      </form>
    </LoopSheet>
  </LoopLayout>
</template>

<style scoped>
.source-guide{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px;margin-bottom:18px}.source-row{display:flex;flex-direction:column;gap:9px}.toolbar,.pager,.event-heading,.event-tags,.event-actions,.withdraw{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.toolbar{margin-bottom:18px}.block{display:block;margin-bottom:14px}.timeline{display:flex;flex-direction:column;gap:16px}.event-card{display:flex;flex-direction:column;gap:14px}.event-heading{justify-content:space-between}.event-statement{font-size:16px;line-height:1.75;white-space:pre-wrap;overflow-wrap:anywhere}.event-meta{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;color:var(--cl-muted);font-size:12px}.confirmation{display:flex;flex-direction:column;gap:7px;padding:14px;border-radius:12px;background:var(--cl-surface-soft)}.participant-list{display:flex;flex-wrap:wrap;gap:8px 16px}.correction{display:flex;flex-direction:column;gap:5px}.verification{padding-left:10px;border-left:3px solid var(--cl-blue)}.withdraw .cl-input{flex:1;min-width:220px}.pager{justify-content:center;margin-top:20px}.history-form{padding:0}.switch-row{display:flex;align-items:center;justify-content:space-between;gap:18px}.switch-row>view{display:flex;flex-direction:column;gap:4px}.date-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.evidence-row{display:grid;grid-template-columns:64px 1fr auto auto;gap:10px;align-items:center}.evidence-row image{width:64px;height:64px;border-radius:10px;background:var(--cl-surface-soft)}
@media(max-width:760px){.source-guide{grid-template-columns:1fr}.event-meta{grid-template-columns:1fr}.evidence-row{grid-template-columns:54px 1fr}.evidence-row image{grid-row:span 2}.evidence-row .cl-btn{width:100%}}
@media(max-width:430px){.date-grid{grid-template-columns:1fr}.toolbar .cl-btn,.event-actions .cl-btn,.withdraw .cl-btn{width:100%}}
</style>
