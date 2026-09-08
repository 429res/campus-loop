<script setup>
import { computed, reactive, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopPicker from '../../components/LoopPicker.vue'
import LoopSegment from '../../components/LoopSegment.vue'
import http, { TOKEN_KEY } from '../../common/http'
import { showAppModal } from '../../common/modal'

const PAGE_SIZE = 6
const authenticated = ref(!!uni.getStorageSync(TOKEN_KEY))
const demands = ref([]), page = ref(1), total = ref(0), loading = ref(false), listError = ref('')
const filter = ref(0), categories = ref([]), categoriesError = ref('')
const offerable = ref([]), offerablePage = ref(1), offerableTotal = ref(0), offerableError = ref(''), offerableLoading = ref(false)
const editorOpen = ref(false), editorBusy = ref(false), actionBusy = ref(null), formError = ref('')
const writeUncertain = ref(false), conflictServer = ref(null), selectionTouched = ref(false)
const selectedItemIds = ref([]), linkedItems = ref([])
const itemCache = ref({})
const form = reactive({ id:null, version:null, categoryIndex:-1, description:'', tags:'' })
let demandRequest = 0, offerableRequest = 0, authRedirecting = false

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))
const offerablePages = computed(() => Math.max(1, Math.ceil(offerableTotal.value / PAGE_SIZE)))
const statusQuery = computed(() => filter.value === 1 ? 'ACTIVE' : filter.value === 2 ? 'INACTIVE' : '')
const selectedCategory = computed(() => categories.value[Number(form.categoryIndex)] || null)
const linkedById = computed(() => new Map(linkedItems.value.map(item => [item.itemId,item])))
const selectedDetails = computed(() => selectedItemIds.value.map(id => linkedById.value.get(id) || itemCache.value[id] || {itemId:id,title:`物品 #${id}`,offerable:true}))

const login = (reason = '') => {
  if (authRedirecting) return
  authRedirecting = true
  uni.redirectTo({url:`/pages/login/login?redirect=demands${reason ? `&reason=${reason}` : ''}`,fail:() => { authRedirecting = false }})
}
const publish = () => uni.switchTab({url:'/pages/publish/publish'})

function handleAuth(error) {
  if (error.status === 401) { authenticated.value = false; editorOpen.value = false; login('session-expired'); return true }
  return false
}

async function loadCategories() {
  categoriesError.value = ''
  try { categories.value = await http.get('/api/categories',{}, {silent:true}) }
  catch (error) { categoriesError.value = error.message }
}

async function loadDemands(nextPage = page.value) {
  if (!uni.getStorageSync(TOKEN_KEY)) return
  const request = ++demandRequest
  loading.value = true; listError.value = ''
  try {
    const data = await http.get('/api/demands',{page:nextPage,size:PAGE_SIZE,...(statusQuery.value ? {status:statusQuery.value} : {})},{silent:true})
    if (request !== demandRequest) return
    demands.value = data.records; total.value = data.total; page.value = data.page
    if (!data.records.length && nextPage > 1) return loadDemands(nextPage - 1)
  } catch (error) { if (request === demandRequest && !handleAuth(error)) listError.value = error.message }
  finally { if (request === demandRequest) loading.value = false }
}

async function loadOfferable(nextPage = offerablePage.value) {
  if (!uni.getStorageSync(TOKEN_KEY)) return
  const request = ++offerableRequest
  offerableLoading.value = true; offerableError.value = ''
  try {
    const data = await http.get('/api/demands/offerable-items',{page:nextPage,size:PAGE_SIZE},{silent:true})
    if (request !== offerableRequest) return
    offerable.value = data.records; offerableTotal.value = data.total; offerablePage.value = data.page
    itemCache.value = {...itemCache.value,...Object.fromEntries(data.records.map(item => [item.itemId,item]))}
  } catch (error) { if (request === offerableRequest && !handleAuth(error)) offerableError.value = error.message }
  finally { if (request === offerableRequest) offerableLoading.value = false }
}

async function init() {
  authRedirecting = false
  authenticated.value = !!uni.getStorageSync(TOKEN_KEY)
  if (!authenticated.value) { editorOpen.value = false; return }
  await Promise.all([loadCategories(),loadDemands(1),loadOfferable(1)])
}

function resetEditor() {
  form.id = null; form.version = null; form.categoryIndex = -1; form.description = ''; form.tags = ''
  selectedItemIds.value = []; linkedItems.value = []; selectionTouched.value = false
  formError.value = ''; conflictServer.value = null; writeUncertain.value = false
}

function openCreate() { resetEditor(); editorOpen.value = true; loadOfferable(1) }

function applyDemand(demand) {
  form.id = demand.id; form.version = demand.version
  form.categoryIndex = categories.value.findIndex(category => category.id === demand.categoryId)
  form.description = demand.description || ''; form.tags = (demand.preferredTags || []).join('，')
  linkedItems.value = demand.offeredItems || []; selectedItemIds.value = linkedItems.value.map(item => item.itemId)
  itemCache.value = {...itemCache.value,...Object.fromEntries(linkedItems.value.map(item => [item.itemId,item]))}
  selectionTouched.value = false; conflictServer.value = null; formError.value = ''
}

async function openEdit(demand) {
  if (editorBusy.value || actionBusy.value) return
  editorBusy.value = true; formError.value = ''
  try { applyDemand(await http.get(`/api/demands/${demand.id}`,{}, {silent:true})); editorOpen.value = true; await loadOfferable(1) }
  catch (error) { if (!handleAuth(error)) { formError.value = error.message; await loadDemands() } }
  finally { editorBusy.value = false }
}

function closeEditor() { if (editorBusy.value) return; editorOpen.value = false; resetEditor() }

function parseTags() {
  const tags = [...new Set(form.tags.split(/[,，]/).map(tag => tag.trim().toLowerCase()).filter(Boolean))]
  if (tags.length > 8 || tags.some(tag => tag.length > 20)) throw new Error('偏好标签最多 8 个，每个不超过 20 个字符')
  return tags
}

function toggleItem(item) {
  if (!item.offerable) return
  const ids = [...selectedItemIds.value], index = ids.indexOf(item.itemId)
  if (index >= 0) ids.splice(index,1)
  else if (ids.length >= 100) { formError.value = '最多关联 100 件可提供物品'; return }
  else ids.push(item.itemId)
  selectedItemIds.value = ids; selectionTouched.value = true; formError.value = ''
}

function removeSelected(id) {
  selectedItemIds.value = selectedItemIds.value.filter(itemId => itemId !== id)
  selectionTouched.value = true
}

async function saveDemand() {
  if (editorBusy.value || writeUncertain.value || conflictServer.value) return
  formError.value = ''
  if (!selectedCategory.value) { formError.value = '请选择想要的分类'; return }
  if (form.description.length > 2000) { formError.value = '需求说明不能超过 2000 个字符'; return }
  let preferredTags
  try { preferredTags = parseTags() } catch (error) { formError.value = error.message; return }
  const editing = !!form.id
  const payload = {categoryId:selectedCategory.value.id,description:form.description,preferredTags}
  if (!editing || selectionTouched.value) payload.offeredItemIds = [...selectedItemIds.value].sort((a,b) => a-b)
  if (editing) payload.version = form.version
  editorBusy.value = true
  try {
    const saved = editing
      ? await http.patch(`/api/demands/${form.id}`,payload,{silent:true,uncertainOnFailure:true})
      : await http.post('/api/demands',payload,{silent:true,uncertainOnFailure:true})
    applyDemand(saved); editorOpen.value = false
    await Promise.all([loadDemands(1),loadOfferable(1)])
    uni.showToast({title:editing ? '需求已保存' : '需求已创建',icon:'success'})
  } catch (error) {
    if (handleAuth(error)) return
    if (error.uncertain) {
      writeUncertain.value = true
      formError.value = '未收到服务器响应，结果无法确认。请先重新读取列表核对，不要直接重复提交。'
    } else if (error.status === 409 && form.id) {
      formError.value = '需求已被另一会话更新。当前输入已保留，请读取服务器版本后核对。'
      try { conflictServer.value = await http.get(`/api/demands/${form.id}`,{}, {silent:true}) } catch (readError) { if (!handleAuth(readError)) formError.value += ` 读取失败：${readError.message}` }
      await loadOfferable(1)
    } else if (error.status === 404) {
      formError.value = '需求已不存在或删除，列表已刷新。'; await loadDemands()
    } else formError.value = error.message
  } finally { editorBusy.value = false }
}

function useServerVersion() { if (conflictServer.value) applyDemand(conflictServer.value) }

async function recoverUncertain() {
  editorBusy.value = true
  try { await Promise.all([loadDemands(1),loadOfferable(1)]); editorOpen.value = false; resetEditor() }
  finally { editorBusy.value = false }
}

async function changeStatus(demand) {
  if (actionBusy.value) return
  const next = demand.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  const confirmation = await showAppModal({title:next === 'INACTIVE' ? '停用需求' : '恢复需求',content:next === 'INACTIVE' ? '停用后仍可查看和编辑，不等同于删除。' : '恢复时服务端会重新核实全部关联物品。'})
  if (!confirmation.confirm) return
  actionBusy.value = demand.id; listError.value = ''
  try {
    await http.patch(`/api/demands/${demand.id}/status`,{version:demand.version,status:next},{silent:true,uncertainOnFailure:true})
    await Promise.all([loadDemands(),loadOfferable(1)])
  } catch (error) {
    if (handleAuth(error)) return
    const message = error.uncertain ? '状态请求结果无法确认，已停止重复提交。请刷新列表核对。' : error.status === 409 ? '需求或关联物品状态已变化，请刷新后核对。' : error.message
    await Promise.all([loadDemands(),loadOfferable(1)])
    listError.value = message
  } finally { actionBusy.value = null }
}

async function deleteDemand(demand) {
  if (actionBusy.value) return
  const confirmation = await showAppModal({title:'删除需求',content:'删除会保留历史墓碑但不可从 API 恢复；停用才是可恢复操作。确认删除？',danger:true,confirmText:'删除'})
  if (!confirmation.confirm) return
  actionBusy.value = demand.id; listError.value = ''
  try {
    await http.delete(`/api/demands/${demand.id}?version=${demand.version}`,{}, {silent:true,uncertainOnFailure:true})
    await Promise.all([loadDemands(),loadOfferable(1)])
  } catch (error) {
    if (handleAuth(error)) return
    const message = error.uncertain ? '删除结果无法确认，请刷新列表核对，不要重复删除。' : error.status === 409 ? '需求已由另一会话更新，请刷新后再决定。' : error.message
    await Promise.all([loadDemands(),loadOfferable(1)])
    listError.value = message
  } finally { actionBusy.value = null }
}

function changeFilter(value) { filter.value = Number(value); loadDemands(1) }
onShow(init)
</script>

<template>
  <LoopLayout>
    <view class="demand-heading"><view class="cl-page-heading"><text class="cl-title">我的独立需求</text><text class="cl-subtitle">需求可以不依附物品保存；关联只来自服务端核实的本人可提供物品。</text></view><LoopButton v-if="authenticated" class="cl-btn cl-btn--primary" :disabled="loading || !!actionBusy" @click="openCreate">＋ 新建需求</LoopButton></view>
    <view class="cl-notice compatibility-note">独立需求保存在需求清单中，当前推荐页仍使用发布物品时填写的需求。本页保存不会修改物品附带的需求。</view>
    <view v-if="!authenticated" class="cl-panel cl-empty"><text class="cl-empty-symbol">◎</text><text>登录后管理本人需求</text><LoopButton class="cl-btn cl-btn--primary" @click="login">重新登录</LoopButton></view>
    <template v-else>
      <view class="demand-toolbar"><LoopSegment :model-value="filter" :options="['全部','进行中','已停用']" @update:model-value="changeFilter"/><text class="cl-hint">共 {{ total }} 条</text></view>
      <view v-if="listError" class="cl-panel inline-state" role="alert"><text class="cl-error">{{ listError }}</text><LoopButton class="cl-btn" :disabled="loading" @click="loadDemands()">重新读取</LoopButton></view>
      <view v-if="loading" class="cl-empty"><text class="cl-label">正在读取需求…</text></view>
      <view v-else-if="!demands.length" class="cl-panel cl-empty"><text class="cl-empty-symbol">◎</text><text>{{ filter ? '当前状态下没有需求' : '还没有独立需求' }}</text><text class="cl-hint">即使暂时没有可提供物品，也可以先保存想要的内容。</text><LoopButton class="cl-btn cl-btn--primary" @click="openCreate">新建需求</LoopButton></view>
      <view v-else class="demand-list"><view v-for="demand in demands" :key="demand.id" class="cl-panel demand-card"><view class="demand-card-head"><view><text class="cl-section-title">{{ demand.categoryName }}</text><text class="cl-hint">版本 {{ demand.version }} · {{ demand.status === 'ACTIVE' ? '进行中' : '已停用' }}</text></view><text class="cl-tag" :class="demand.status === 'ACTIVE' ? 'cl-tag--pink' : 'cl-tag--muted'">{{ demand.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE' }}</text></view><text class="demand-description">{{ demand.description || '未填写补充说明' }}</text><view class="tag-row"><text v-for="tag in demand.preferredTags" :key="tag" class="cl-tag"># {{ tag }}</text><text v-if="!demand.preferredTags.length" class="cl-hint">未设置偏好标签</text></view><view class="offered-summary"><text class="cl-field-title">我可提供 {{ demand.offeredItems.length }} 件</text><text v-if="!demand.offeredItems.length" class="cl-hint">仍可管理需求，但没有候选物品时暂不能组成交换环。</text><text v-for="item in demand.offeredItems" :key="item.itemId" class="offered-line">{{ item.title || `物品 #${item.itemId}` }} · {{ item.offerable ? '当前可提供' : '已失效，需核对' }}</text></view><view class="card-actions"><LoopButton class="cl-btn" :disabled="!!actionBusy || editorBusy" @click="openEdit(demand)">编辑</LoopButton><LoopButton class="cl-btn" :disabled="!!actionBusy" @click="changeStatus(demand)">{{ demand.status === 'ACTIVE' ? '停用' : '恢复' }}</LoopButton><LoopButton class="cl-btn demand-delete" :disabled="!!actionBusy" @click="deleteDemand(demand)">删除</LoopButton></view></view></view>
      <view v-if="totalPages>1" class="pagination"><LoopButton class="cl-btn" :disabled="loading || page<=1" @click="loadDemands(page-1)">上一页</LoopButton><text class="cl-label">{{ page }} / {{ totalPages }}</text><LoopButton class="cl-btn" :disabled="loading || page>=totalPages" @click="loadDemands(page+1)">下一页</LoopButton></view>
    </template>

    <view v-if="editorOpen" class="editor-grid">
      <form class="cl-panel cl-form" @submit="saveDemand"><view class="editor-head"><view><text class="cl-section-title">{{ form.id ? '编辑需求' : '新建需求' }}</text><text v-if="form.id" class="cl-hint">正在编辑服务器版本 {{ form.version }}</text></view><LoopButton class="cl-btn" :disabled="editorBusy" @click="closeEditor">关闭</LoopButton></view><view class="cl-field"><text class="cl-field-title">想要的分类</text><LoopPicker aria-label="想要的分类" :range="categories" range-key="name" :value="form.categoryIndex" :disabled="editorBusy || writeUncertain || !!conflictServer" @change="form.categoryIndex=Number($event.detail.value)"><view class="cl-picker">{{ selectedCategory?.name || '请选择分类' }}⌄</view></LoopPicker><view v-if="categoriesError" class="inline-state"><text class="cl-error">分类读取失败：{{ categoriesError }}</text><LoopButton class="cl-btn" @click="loadCategories">重试</LoopButton></view></view><view class="cl-field"><text class="cl-field-title">需求说明</text><textarea v-model="form.description" class="cl-textarea" aria-label="需求说明" maxlength="2000" :disabled="editorBusy || writeUncertain || !!conflictServer" placeholder="可留空；说明使用场景、可接受范围等"/><text class="cl-hint">{{ form.description.length }} / 2000</text></view><view class="cl-field"><text class="cl-field-title">偏好标签</text><input v-model="form.tags" class="cl-input" aria-label="偏好标签" maxlength="200" :disabled="editorBusy || writeUncertain || !!conflictServer" placeholder="逗号分隔，最多 8 个，每项 20 字" /></view><text v-if="formError" class="cl-error" role="alert">{{ formError }}</text><view v-if="conflictServer" class="conflict-box"><text class="cl-field-title">服务器当前版本 {{ conflictServer.version }}</text><text class="cl-hint">{{ conflictServer.categoryName }} · {{ conflictServer.description || '无说明' }} · {{ conflictServer.preferredTags.join('、') || '无标签' }}</text><LoopButton class="cl-btn" @click="useServerVersion">使用服务器内容重新编辑</LoopButton></view><view v-if="writeUncertain" class="cl-notice"><text>当前输入仍保留，但不能直接重放。重新读取列表后请核对是否已有对应变化。</text><LoopButton class="cl-btn" :disabled="editorBusy" @click="recoverUncertain">重新读取并结束本次提交</LoopButton></view><LoopButton class="cl-btn cl-btn--primary cl-btn--wide" form-type="submit" :disabled="editorBusy || writeUncertain || !!conflictServer || !!categoriesError">{{ editorBusy ? '保存中…' : form.id ? '保存修改' : '创建需求' }}</LoopButton></form>
      <view class="cl-panel offer-panel"><view class="editor-head"><view><text class="cl-section-title">我有什么</text><text class="cl-hint">只显示服务端确认属于本人、AVAILABLE 且未占用的物品。</text></view><text class="cl-tag">已选 {{ selectedItemIds.length }} / 100</text></view><view v-if="selectedDetails.length" class="selected-list"><view v-for="item in selectedDetails" :key="item.itemId" class="selected-item"><text>{{ item.title || `物品 #${item.itemId}` }}</text><text v-if="item.offerable===false" class="cl-error">关联已失效</text><LoopButton class="cl-icon-btn" aria-label="移除关联物品" :disabled="editorBusy || writeUncertain || !!conflictServer" @click="removeSelected(item.itemId)">×</LoopButton></view></view><view v-if="offerableLoading" class="cl-empty compact"><text class="cl-label">正在读取本人可提供物品…</text></view><view v-else-if="offerableError" class="inline-state" role="alert"><text class="cl-error">{{ offerableError }}</text><LoopButton class="cl-btn" @click="loadOfferable()">重试</LoopButton></view><view v-else-if="!offerable.length" class="cl-empty compact"><text>当前没有可提供物品</text><text class="cl-hint">需求仍可保存和管理；发布 AVAILABLE 物品后再关联，当前暂不能组成交换环。</text><LoopButton class="cl-btn" @click="publish">发布物品</LoopButton></view><view v-else class="offerable-list"><LoopButton v-for="item in offerable" :key="item.itemId" class="offerable-item" :class="{selected:selectedItemIds.includes(item.itemId)}" :aria-pressed="selectedItemIds.includes(item.itemId)" :disabled="editorBusy || writeUncertain || !!conflictServer" @click="toggleItem(item)"><view><text class="cl-field-title">{{ item.title }}</text><text class="cl-hint">成色 {{ item.conditionLevel }} / 5 · AVAILABLE</text></view><text>{{ selectedItemIds.includes(item.itemId) ? '已关联 ✓' : '选择' }}</text></LoopButton></view><view v-if="offerablePages>1" class="pagination"><LoopButton class="cl-btn" :disabled="offerableLoading || offerablePage<=1" @click="loadOfferable(offerablePage-1)">上一页</LoopButton><text class="cl-label">{{ offerablePage }} / {{ offerablePages }}</text><LoopButton class="cl-btn" :disabled="offerableLoading || offerablePage>=offerablePages" @click="loadOfferable(offerablePage+1)">下一页</LoopButton></view></view>
    </view>
  </LoopLayout>
</template>

<style scoped>
.demand-heading,.demand-toolbar,.editor-head,.card-actions,.pagination,.inline-state{display:flex;align-items:center;justify-content:space-between;gap:14px}.demand-heading .cl-page-heading{flex:1}.compatibility-note{margin-bottom:22px}.demand-toolbar{margin:20px 0}.demand-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.demand-card{display:flex;flex-direction:column;gap:18px}.demand-card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.demand-description{line-height:1.75;overflow-wrap:anywhere}.tag-row{display:flex;gap:7px;flex-wrap:wrap}.offered-summary{display:flex;flex-direction:column;gap:7px;padding:14px;border-radius:12px;background:var(--cl-surface-soft)}.offered-line{font-size:12px;color:var(--cl-muted);overflow-wrap:anywhere}.card-actions{justify-content:flex-start;margin-top:auto;flex-wrap:wrap}.demand-delete{color:var(--cl-danger)}.pagination{justify-content:center;margin:22px 0}.inline-state{margin:16px 0}.inline-state>text{flex:1}.editor-grid{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(300px,.9fr);gap:20px;margin-top:28px;align-items:start}.offer-panel{display:flex;flex-direction:column;gap:16px}.selected-list,.offerable-list{display:flex;flex-direction:column;gap:10px}.selected-item{display:grid;grid-template-columns:minmax(0,1fr) auto auto;align-items:center;gap:10px;padding:10px 12px;border:1px solid var(--cl-border);border-radius:12px}.offerable-item{width:100%;display:flex;align-items:center;justify-content:space-between;gap:12px;text-align:left;background:var(--cl-surface-soft);border:1px solid var(--cl-border);border-radius:12px;padding:12px}.offerable-item.selected{border-color:var(--cl-primary);background:var(--cl-primary-soft)}.offerable-item>view{display:flex;flex-direction:column;gap:4px;min-width:0}.compact{padding:28px 12px}.conflict-box{display:flex;flex-direction:column;gap:12px;padding:16px;border:1px solid var(--cl-danger);border-radius:12px;background:var(--cl-surface-soft)}@media(max-width:820px){.demand-list,.editor-grid{grid-template-columns:1fr}}@media(max-width:520px){.demand-heading,.demand-toolbar{align-items:stretch;flex-direction:column}.demand-heading>.cl-btn{width:100%}.card-actions>.cl-btn{flex:1}.inline-state{align-items:stretch;flex-direction:column}.selected-item{grid-template-columns:minmax(0,1fr) auto}.selected-item>.cl-error{grid-column:1/-1}}
</style>
