<script setup>
import LoopIcon from '../../components/LoopIcon.vue'
import LoopButton from '../../components/LoopButton.vue'
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import ItemCard from '../../components/ItemCard.vue'
import http, { isAbortError, TOKEN_KEY } from '../../common/http'
import { favoriteIds, readAllFavorites, setFavorite } from '../../common/favorites.mjs'
const categories = ref([]), items = ref([]), keyword = ref(''), selected = ref(''), total = ref(0), page = ref(1), loading = ref(false), error = ref(''), categoriesError = ref('')
const size = 12
const favorites = ref(new Set()), favoriteBusy = ref(new Set()), favoriteNotice = ref('')
let favoriteSequence = 0
let sequence = 0
let activeRequest
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size)))
async function load() {
  activeRequest?.abort?.()
  const requestId = ++sequence
  const requestedPage = page.value
  loading.value = true; error.value = ''
  try {
    activeRequest = http.get('/api/items', { page:requestedPage,size,keyword:keyword.value.trim(),...(selected.value ? {categoryId:selected.value} : {}) }, {silent:true})
    const data = await activeRequest
    if (requestId !== sequence) return
    const records = Array.isArray(data?.records) ? data.records : []
    const count = Number(data?.total) || 0
    const lastPage = Math.max(1, Math.ceil(count / size))
    if (requestedPage > lastPage) { page.value = lastPage; return load() }
    items.value = records; total.value = count
  } catch (e) { if(requestId === sequence && !isAbortError(e)) error.value = e.message }
  finally { if(requestId === sequence) loading.value = false }
}
async function loadCategories() {
  categoriesError.value = ''
  try { categories.value = await http.get('/api/categories',{includeInactive:true}, {silent:true}) }
  catch (e) { if (!isAbortError(e)) categoriesError.value = e.message }
}
async function loadFavorites() {
  const requestId = ++favoriteSequence
  const token = uni.getStorageSync(TOKEN_KEY)
  favoriteNotice.value = ''
  if (!token) { favorites.value = new Set(); return }
  try {
    const records = await readAllFavorites({silent:true})
    if (requestId === favoriteSequence && token === uni.getStorageSync(TOKEN_KEY)) favorites.value = favoriteIds(records)
  } catch (e) { if (requestId === favoriteSequence && token === uni.getStorageSync(TOKEN_KEY) && !isAbortError(e)) favoriteNotice.value = e.message }
}
async function toggleFavorite(item) {
  const id = String(item.id)
  const token = uni.getStorageSync(TOKEN_KEY)
  if (!token) { uni.navigateTo({url:'/pages/login/login'}); return }
  if (favoriteBusy.value.has(id)) return
  favoriteBusy.value = new Set([...favoriteBusy.value,id]); favoriteNotice.value = ''
  const next = !favorites.value.has(id)
  try {
    await setFavorite(id,next)
    if (token !== uni.getStorageSync(TOKEN_KEY)) return
    const updated = new Set(favorites.value); next ? updated.add(id) : updated.delete(id); favorites.value = updated
  } catch (e) {
    if (token !== uni.getStorageSync(TOKEN_KEY)) return
    favoriteNotice.value = e.uncertain ? '收藏操作结果无法确认，已重新读取收藏夹。' : e.message
    if (e.uncertain) await loadFavorites()
  } finally { const updated = new Set(favoriteBusy.value); updated.delete(id); favoriteBusy.value = updated }
}
async function init() { await Promise.all([loadCategories(), load(), loadFavorites()]) }
function search() { page.value = 1; load() }
function choose(id) { selected.value = id; search() }
function changePage(delta) { const next = page.value + delta; if (next < 1 || next > totalPages.value || loading.value) return; page.value = next; load() }
const goMatch = () => uni.switchTab({url:'/pages/matches/matches'})
onShow(init)
onPullDownRefresh(async () => { await init(); uni.stopPullDownRefresh() })
onUnload(() => { sequence++; favoriteSequence++; activeRequest?.abort?.() })
</script>
<template>
  <LoopLayout active-tab="home">
    <view class="search-row"><view class="search-field"><LoopIcon name="search" :size="20"/><input v-model="keyword" class="search-input" placeholder="搜一搜，让需要与闲置相遇" aria-label="搜索物品" confirm-type="search" @confirm="search"/><LoopButton v-if="keyword" class="cl-icon-btn clear-search" aria-label="清空搜索" @click="keyword='';search()"><LoopIcon name="close" :size="18"/></LoopButton><LoopButton class="cl-btn cl-btn--primary" @click="search">{{ loading ? '搜索中…' : '搜索' }}</LoopButton></view><text class="search-note cl-desktop-only">每一件闲置，都值得下一次心动</text></view>
    <view class="home-hero"><view class="hero-copy"><text class="hero-eyebrow">校园里的下一次相遇</text><text class="hero-title">让闲置，<text class="hero-accent">继续有用。</text></text><text class="hero-subtitle">找到需要的好物，也为你的闲置找到新主人。</text><LoopButton class="cl-btn hero-button" @click="goMatch">寻找交换灵感 <LoopIcon name="arrow" tone="primary" :size="18"/></LoopButton></view><view class="hero-visual" aria-hidden="true"><image src="/static/demo/book.svg" mode="aspectFit"/><text>旧物 · 新的校园故事</text></view></view>
    <view class="category-row" role="group" aria-label="物品分类"><LoopButton class="category-button" :class="{active:selected===''}" :aria-pressed="selected===''" @click="choose('')">全部好物</LoopButton><LoopButton v-for="category in categories" :key="category.id" class="category-button" :class="{active:selected===category.id}" :aria-pressed="selected===category.id" @click="choose(category.id)">{{ category.name }}</LoopButton><LoopButton v-if="categoriesError" class="category-button category-retry" @click="loadCategories">分类加载失败，重试</LoopButton></view>
    <view class="cl-section-heading"><view><text class="cl-section-title">发现校园好物</text><text class="section-caption">给物品一次循环，给生活一点惊喜</text></view><text class="cl-label">{{ total }} 件可发现的闲置</text></view>
    <view v-if="error" class="cl-panel cl-empty" role="alert"><LoopIcon name="loop" tone="primary" :size="36"/><text>{{ error }}</text><LoopButton class="cl-btn" @click="load">重试当前结果</LoopButton></view>
    <view v-else-if="loading" class="cl-empty"><text class="cl-label">正在寻找校园好物…</text></view>
    <view v-else-if="!items.length" class="cl-panel cl-empty"><LoopIcon name="search" tone="primary" :size="36"/><text>{{ keyword.trim() || selected ? '没有符合条件的物品' : '还没有可发现的物品' }}</text><text class="cl-hint">{{ keyword.trim() || selected ? '换个关键词或分类再试试。' : '发布第一件闲置，让它开始新的旅程。' }}</text></view>
    <text v-if="favoriteNotice" class="cl-error favorite-notice" role="alert">{{ favoriteNotice }}</text>
    <view v-if="!error && !loading && items.length" class="item-grid"><ItemCard v-for="item in items" :key="item.id" :item="item" :show-favorite="!!uni.getStorageSync(TOKEN_KEY)" :favorited="favorites.has(String(item.id))" :favorite-busy="favoriteBusy.has(String(item.id))" @favorite="toggleFavorite" /></view>
    <view class="pagination" v-if="total > size"><LoopButton class="cl-btn" :disabled="page === 1 || loading" @click="changePage(-1)">上一页</LoopButton><text class="cl-label">{{ page }} / {{ totalPages }}</text><LoopButton class="cl-btn" :disabled="page >= totalPages || loading" @click="changePage(1)">下一页</LoopButton></view>
    <view class="home-bottom"><view><text class="home-bottom-title">两人刚刚好，三人也可以。</text><text class="cl-subtitle">用有方向的需求匹配，发现意想不到的交换环。</text></view><LoopButton class="cl-btn cl-btn--blue" @click="goMatch">了解循环交换 <LoopIcon name="arrow" :size="18"/></LoopButton></view>
  </LoopLayout>
</template>
<style scoped>
.search-row{display:flex;align-items:center;gap:28px;margin:8px 0 24px}.search-field{display:flex;align-items:center;gap:10px;padding:6px 7px 6px 16px;background:var(--cl-surface);border:1px solid var(--cl-border);border-radius:16px;flex:1;max-width:720px}.search-field:focus-within{border-color:var(--cl-blue);box-shadow:0 0 0 3px var(--cl-blue-soft)}.search-symbol{font-size:29px;color:var(--cl-muted)}.search-input{flex:1;min-width:0;font-size:14px;height:38px}.search-field .cl-btn{min-height:38px;padding:8px 24px;border-radius:11px}.clear-search{border:0;min-height:34px;width:30px;background:transparent;box-shadow:none}.search-note{font-size:12px;color:var(--cl-muted)}
 .home-hero{display:grid;grid-template-columns:minmax(0,1fr) minmax(180px,.55fr);align-items:center;gap:24px;padding:36px 42px;border-radius:24px;background:linear-gradient(115deg,var(--cl-primary-soft),var(--cl-surface));border:1px solid var(--cl-border)}
.hero-copy{min-width:0}.hero-eyebrow{display:block;font-size:12px;letter-spacing:1.5px;color:var(--cl-primary)}.hero-title{display:block;font-size:40px;font-weight:750;letter-spacing:-1px;line-height:1.3;margin:14px 0}.hero-accent{color:var(--cl-primary)}.hero-subtitle{display:block;font-size:14px;line-height:1.8;color:var(--cl-muted)}.hero-button{margin-top:22px;gap:12px;background:var(--cl-surface);color:var(--cl-primary);align-self:flex-start}.hero-visual{display:flex;flex-direction:column;align-items:center;gap:12px}.hero-visual image{width:100%;max-width:230px;height:150px;border-radius:16px}.hero-visual text{font-size:12px;color:var(--cl-muted)}
.category-row{display:flex;align-items:center;gap:12px;padding:24px 0 3px;overflow-x:auto}.category-button{display:flex;align-items:center;gap:9px;flex-shrink:0;padding:10px 14px;border-radius:13px;font-size:12px;background:var(--cl-surface);border:1px solid transparent;color:var(--cl-muted);transition:background-color 180ms,border-color 180ms}.category-button.active{background:var(--cl-primary-soft);border-color:var(--cl-primary);color:var(--cl-primary)}.category-retry{color:var(--cl-danger);border-color:var(--cl-danger)}.category-icon{display:flex;width:28px;height:28px;border-radius:9px;align-items:center;justify-content:center;background:var(--cl-primary-soft);color:var(--cl-primary);font-size:20px}.category-tone-0,.category-tone-2{background:var(--cl-blue-soft);color:var(--cl-blue)}.category-tone-1{background:#f6f0df;color:#9d6f31}.category-tone-3{background:#e8f2eb;color:#43764c}.section-caption{font-size:12px;color:var(--cl-muted);margin-left:18px}.item-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px}.pagination{display:flex;justify-content:center;align-items:center;gap:20px;margin-top:28px}.home-bottom{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:25px 30px;margin-top:36px;border-radius:18px;background:var(--cl-blue-soft)}.home-bottom-title{display:block;font-size:20px;font-weight:700;margin-bottom:6px}
@media(max-width:1000px){.item-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.section-caption{display:none}}
@media(max-width:650px){.search-row{margin:2px 0 18px}.search-field{padding:6px 8px;gap:8px}.search-field .cl-btn{padding:8px 14px}.search-input{font-size:14px}.home-hero{grid-template-columns:1fr;padding:24px;gap:0;border-radius:20px}.hero-title{font-size:30px;margin:12px 0}.hero-accent{display:block}.hero-visual{display:none}.hero-subtitle{font-size:13px}.hero-button{margin-top:18px}.category-row{gap:8px;padding:18px 2px 6px}.category-button{padding:10px 16px;font-size:13px;min-height:44px}.cl-section-heading{margin:24px 0 16px;align-items:flex-start;flex-wrap:wrap}.cl-section-title{font-size:20px}.cl-section-heading .cl-label{font-size:12px}.item-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.home-bottom{padding:20px;flex-direction:column;align-items:flex-start}.home-bottom-title{font-size:18px}.home-bottom .cl-subtitle{font-size:13px}}
.category-button{min-height:44px;border-radius:999px;line-height:1.4;max-width:240px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.category-button.active{border-color:transparent;background:var(--cl-primary-soft)}.clear-search{width:36px;flex:none}.search-field{min-width:0}.search-note{flex:none}
</style>
