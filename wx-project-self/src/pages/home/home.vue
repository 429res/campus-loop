<script setup>
import LoopButton from '../../components/LoopButton.vue'
import { computed, ref } from 'vue'
import { onPullDownRefresh, onShow, onUnload } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import ItemCard from '../../components/ItemCard.vue'
import http, { isAbortError } from '../../common/http'
const categories = ref([]), items = ref([]), keyword = ref(''), selected = ref(''), total = ref(0), page = ref(1), loading = ref(false), error = ref(''), categoriesError = ref('')
const size = 12
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
async function init() { await Promise.all([loadCategories(), load()]) }
function search() { page.value = 1; load() }
function choose(id) { selected.value = id; search() }
function changePage(delta) { const next = page.value + delta; if (next < 1 || next > totalPages.value || loading.value) return; page.value = next; load() }
const goMatch = () => uni.switchTab({url:'/pages/matches/matches'})
onShow(init)
onPullDownRefresh(async () => { await init(); uni.stopPullDownRefresh() })
onUnload(() => { sequence++; activeRequest?.abort?.() })
</script>
<template>
  <LoopLayout>
    <view class="search-row"><view class="search-field"><text class="search-symbol">⌕</text><input v-model="keyword" class="search-input" placeholder="搜一搜，让需要与闲置相遇" aria-label="搜索物品" confirm-type="search" @confirm="search"/><LoopButton v-if="keyword" class="cl-icon-btn clear-search" aria-label="清空搜索" @click="keyword='';search()">×</LoopButton><LoopButton class="cl-btn cl-btn--primary" @click="search">{{ loading ? '搜索中…' : '搜索' }}</LoopButton></view><text class="search-note cl-desktop-only">每一件闲置，都值得下一次心动</text></view>
    <view class="home-hero"><view class="hero-copy"><text class="hero-eyebrow">新学期 · 旧物新旅程</text><text class="hero-title">你的闲置，<text class="hero-accent">刚好是我的需要。</text></text><text class="hero-subtitle">从一本读过的书，到一段新的校园故事。<br/>让好物在我们之间，继续发光。</text><LoopButton class="cl-btn hero-button" @click="goMatch">寻找交换灵感 <text>↗</text></LoopButton></view><view class="hero-visual" aria-hidden="true"><view class="hero-orbit"></view><view class="hero-float hero-book"><image src="/static/demo/book.svg" mode="aspectFit"/><text>一本书的新主人</text></view><view class="hero-float hero-camera"><image src="/static/demo/camera.svg" mode="aspectFit"/><text>下一次记录校园</text></view><text class="hero-loop">↻</text><text class="hero-small-label">GOOD THINGS GO AROUND</text></view></view>
    <view class="category-row"><LoopButton class="category-button" :class="{active:selected===''}" @click="choose('')"><text class="category-icon">✳</text><text>全部好物</text></LoopButton><LoopButton v-for="(category,index) in categories" :key="category.id" class="category-button" :class="{active:selected===category.id}" @click="choose(category.id)"><text class="category-icon" :class="`category-tone-${index%4}`">{{ ['▤','◉','⌂','♧','♫','◇'][index%6] }}</text><text>{{ category.name }}</text></LoopButton><LoopButton v-if="categoriesError" class="category-button category-retry" @click="loadCategories">分类加载失败，重试</LoopButton></view>
    <view class="cl-section-heading"><view><text class="cl-section-title">发现校园好物</text><text class="section-caption">给物品一次循环，给生活一点惊喜</text></view><text class="cl-label">{{ total }} 件可发现的闲置</text></view>
    <view v-if="error" class="cl-panel cl-empty" role="alert"><text class="cl-empty-symbol">↺</text><text>{{ error }}</text><LoopButton class="cl-btn" @click="load">重试当前结果</LoopButton></view>
    <view v-else-if="loading" class="cl-empty"><text class="cl-label">正在寻找校园好物…</text></view>
    <view v-else-if="!items.length" class="cl-panel cl-empty"><text class="cl-empty-symbol">⌕</text><text>{{ keyword.trim() || selected ? '没有符合条件的物品' : '还没有可发现的物品' }}</text><text class="cl-hint">{{ keyword.trim() || selected ? '换个关键词或分类再试试。' : '发布第一件闲置，让它开始新的旅程。' }}</text></view>
    <view v-else class="item-grid"><ItemCard v-for="item in items" :key="item.id" :item="item" /></view>
    <view class="pagination" v-if="total > size"><LoopButton class="cl-btn" :disabled="page === 1 || loading" @click="changePage(-1)">上一页</LoopButton><text class="cl-label">{{ page }} / {{ totalPages }}</text><LoopButton class="cl-btn" :disabled="page >= totalPages || loading" @click="changePage(1)">下一页</LoopButton></view>
    <view class="home-bottom"><view><text class="home-bottom-title">两人刚刚好，三人也可以。</text><text class="cl-subtitle">用有方向的需求匹配，发现意想不到的交换环。</text></view><LoopButton class="cl-btn cl-btn--blue" @click="goMatch">了解循环交换 ↗</LoopButton></view>
  </LoopLayout>
</template>
<style scoped>
.search-row{display:flex;align-items:center;gap:28px;margin:8px 0 24px}.search-field{display:flex;align-items:center;gap:10px;padding:6px 7px 6px 16px;background:var(--cl-surface);border:1px solid var(--cl-border);border-radius:16px;flex:1;max-width:720px}.search-field:focus-within{border-color:var(--cl-blue);box-shadow:0 0 0 3px var(--cl-blue-soft)}.search-symbol{font-size:29px;color:var(--cl-muted)}.search-input{flex:1;min-width:0;font-size:14px;height:38px}.search-field .cl-btn{min-height:38px;padding:8px 24px;border-radius:11px}.clear-search{border:0;min-height:34px;width:30px;background:transparent;box-shadow:none}.search-note{font-size:12px;color:var(--cl-muted)}
.home-hero{display:grid;grid-template-columns:1.1fr 1fr;min-height:286px;border-radius:22px;overflow:hidden;background:#faedf2;position:relative}.hero-copy{padding:38px 42px;z-index:1}.hero-eyebrow{font-size:11px;letter-spacing:2px;color:#9a5670}.hero-title{display:block;font-size:34px;font-weight:800;letter-spacing:-1px;line-height:1.5;margin:12px 0;color:#392a35}.hero-accent{display:block;color:#bd3d6d}.hero-subtitle{display:block;font-size:13px;line-height:1.9;color:#7b5c6a}.hero-button{margin-top:24px;min-height:40px;background:#fff;color:#ae315f;border:0;padding:9px 17px;font-size:12px;gap:20px}.hero-visual{position:relative;min-height:270px;overflow:hidden;background:radial-gradient(ellipse at center,rgba(255,255,255,.8),transparent 65%)}.hero-orbit{position:absolute;left:14%;top:11%;width:76%;aspect-ratio:1;border:1px dashed #dfb6c6;border-radius:50%}.hero-float{position:absolute;background:#fff;padding:8px;border-radius:16px;box-shadow:0 12px 28px #98547113;width:45%}.hero-float image{width:100%;height:130px;border-radius:10px}.hero-float text{display:block;font-size:10px;color:#685661;margin:5px 4px}.hero-book{left:1%;top:20px;transform:rotate(-8deg)}.hero-camera{right:8%;top:100px;transform:rotate(9deg)}.hero-loop{position:absolute;left:48%;top:31px;font-size:54px;color:#bd698a;transform:rotate(23deg)}.hero-small-label{position:absolute;bottom:20px;left:18%;font-size:8px;letter-spacing:2px;color:#a7788a}
.category-row{display:flex;align-items:center;gap:12px;padding:24px 0 3px;overflow-x:auto}.category-button{display:flex;align-items:center;gap:9px;flex-shrink:0;padding:10px 14px;border-radius:13px;font-size:12px;background:var(--cl-surface);border:1px solid transparent;color:var(--cl-muted);transition:background-color 180ms,border-color 180ms}.category-button.active{background:var(--cl-primary-soft);border-color:var(--cl-primary);color:var(--cl-primary)}.category-retry{color:var(--cl-danger);border-color:var(--cl-danger)}.category-icon{display:flex;width:28px;height:28px;border-radius:9px;align-items:center;justify-content:center;background:var(--cl-primary-soft);color:var(--cl-primary);font-size:20px}.category-tone-0,.category-tone-2{background:var(--cl-blue-soft);color:var(--cl-blue)}.category-tone-1{background:#f6f0df;color:#9d6f31}.category-tone-3{background:#e8f2eb;color:#43764c}.section-caption{font-size:12px;color:var(--cl-muted);margin-left:18px}.item-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:20px}.pagination{display:flex;justify-content:center;align-items:center;gap:20px;margin-top:28px}.home-bottom{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:25px 30px;margin-top:36px;border-radius:18px;background:var(--cl-blue-soft)}.home-bottom-title{display:block;font-size:20px;font-weight:700;margin-bottom:6px}
@media(min-width:1450px){.hero-float image{height:146px}}
@media(max-width:1000px){.item-grid{grid-template-columns:repeat(3,minmax(0,1fr))}.hero-title{font-size:29px}.hero-copy{padding:30px}.section-caption{display:none}.hero-camera{right:7%;top:120px}.hero-float{width:47%}}
@media(max-width:650px){.search-row{margin:2px 0 18px}.search-field{padding-left:12px;gap:6px}.search-field .cl-btn{padding:8px 16px}.search-input{font-size:12px}.home-hero{grid-template-columns:1fr;min-height:0;border-radius:18px}.hero-copy{padding:25px;min-height:244px}.hero-title{font-size:25px;line-height:1.5;margin:10px 0}.hero-subtitle{font-size:11px}.hero-button{margin-top:17px}.hero-visual{position:absolute;width:130px;height:160px;min-height:0;right:-24px;bottom:-9px;opacity:.72}.hero-orbit,.hero-book,.hero-loop,.hero-small-label{display:none}.hero-camera{width:100%;right:0;top:0;transform:rotate(10deg)}.hero-camera image{height:95px}.hero-camera text{font-size:8px}.category-row{gap:8px;padding-top:18px}.category-button{padding:8px 11px;font-size:11px}.category-icon{height:24px;width:24px;font-size:17px}.cl-section-heading{margin:25px 0 16px;align-items:flex-end}.cl-section-title{font-size:19px}.cl-section-heading .cl-label{font-size:10px}.item-grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.home-bottom{padding:20px;flex-direction:column;align-items:flex-start}.home-bottom-title{font-size:18px}.home-bottom .cl-subtitle{font-size:12px}}
</style>
