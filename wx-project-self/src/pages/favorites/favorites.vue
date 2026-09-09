<script setup>
import LoopIcon from '../../components/LoopIcon.vue'
import { computed, ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import ItemCard from '../../components/ItemCard.vue'
import http, { TOKEN_KEY, isAbortError } from '../../common/http'
import { setFavorite } from '../../common/favorites.mjs'

const records = ref([]), total = ref(0), page = ref(1), loading = ref(false), error = ref(''), notice = ref('')
const busy = ref(new Set()), size = 12
let sequence = 0, activeRequest
const totalPages = computed(() => Math.max(1,Math.ceil(total.value / size)))
const login = () => uni.navigateTo({url:'/pages/login/login?redirect=favorites'})

async function load({recovery = false} = {}) {
  const requestId = ++sequence
  const token = uni.getStorageSync(TOKEN_KEY)
  activeRequest?.abort?.(); error.value = ''; if (!recovery) notice.value = ''
  if (!token) { records.value = []; total.value = 0; loading.value = false; return }
  loading.value = true
  try {
    activeRequest = http.get('/api/favorites',{page:page.value,size},{silent:true})
    const data = await activeRequest
    if (requestId !== sequence || token !== uni.getStorageSync(TOKEN_KEY)) return
    records.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total || 0)
    const last = Math.max(1,Math.ceil(total.value / size))
    if (page.value > last) { page.value = last; return load({recovery}) }
    if (recovery) notice.value = '已从服务器重新读取收藏结果。'
  } catch (e) {
    if (requestId !== sequence || token !== uni.getStorageSync(TOKEN_KEY) || isAbortError(e)) return
    error.value = e.message
  } finally { if (requestId === sequence) loading.value = false }
}

async function remove(record) {
  const id = String(record.itemId)
  const token = uni.getStorageSync(TOKEN_KEY)
  if (busy.value.has(id)) return
  busy.value = new Set([...busy.value,id]); notice.value = ''; error.value = ''
  try { await setFavorite(id,false); if (token === uni.getStorageSync(TOKEN_KEY)) await load() }
  catch (e) {
    if (token !== uni.getStorageSync(TOKEN_KEY)) return
    if (e.uncertain) { notice.value = '取消结果无法确认，正在从服务器核实。'; await load({recovery:true}) }
    else error.value = e.message
  } finally { const next = new Set(busy.value); next.delete(id); busy.value = next }
}
function changePage(delta) { const next = page.value + delta; if (loading.value || next < 1 || next > totalPages.value) return; page.value = next; load() }
onShow(load)
onUnload(() => { sequence++; activeRequest?.abort?.() })
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的收藏</text><text class="cl-subtitle">收藏由服务端保存；物品状态不代表占用或可交换保证。</text></view>
    <view v-if="!uni.getStorageSync(TOKEN_KEY)" class="cl-panel cl-empty"><LoopIcon name="heart" tone="primary" :size="36"/><text>登录后查看跨设备收藏</text><LoopButton class="cl-btn cl-btn--primary" @click="login">登录</LoopButton></view>
    <view v-else-if="loading" class="cl-empty"><text class="cl-label">正在读取收藏夹…</text></view>
    <view v-else-if="error" class="cl-panel cl-empty" role="alert"><text class="cl-error">{{ error }}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton><LoopButton class="cl-btn" @click="login">重新登录</LoopButton></view>
    <view v-else-if="!records.length" class="cl-panel cl-empty"><LoopIcon name="heart" tone="primary" :size="36"/><text>还没有收藏物品</text><text class="cl-hint">在发现页或物品详情中收藏，刷新后仍会从服务器读取。</text></view>
    <template v-else>
      <text v-if="notice" class="cl-notice" role="status">{{ notice }}</text>
      <view class="favorite-grid">
        <view v-for="record in records" :key="record.itemId" class="favorite-entry">
          <ItemCard v-if="record.itemVisible && record.item" :item="record.item" :show-favorite="true" :favorited="true" :favorite-busy="busy.has(String(record.itemId))" @favorite="remove(record)" />
          <view v-else class="cl-panel unavailable"><text class="unavailable-symbol">◇</text><text class="cl-section-title">物品暂不可见</text><text class="cl-hint">物品 #{{ record.itemId }} · 收藏于 {{ record.favoritedAt?.slice(0,10) || '未知时间' }}</text><text class="cl-hint">可能已下架或不再公开；这里不会展示旧缓存，也不能打开详情。</text><LoopButton class="cl-btn" :disabled="busy.has(String(record.itemId))" @click="remove(record)">{{ busy.has(String(record.itemId)) ? '取消中…' : '取消收藏' }}</LoopButton></view>
        </view>
      </view>
      <view class="pagination"><LoopButton class="cl-btn" :disabled="page===1 || loading" @click="changePage(-1)">上一页</LoopButton><text class="cl-label">{{ page }} / {{ totalPages }} · 共 {{ total }} 条</text><LoopButton class="cl-btn" :disabled="page>=totalPages || loading" @click="changePage(1)">下一页</LoopButton></view>
    </template>
  </LoopLayout>
</template>

<style scoped>
.favorite-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px}.favorite-entry{min-width:0}.unavailable{min-height:260px;display:flex;flex-direction:column;align-items:flex-start;justify-content:center;gap:12px}.unavailable-symbol{font-size:34px;color:var(--cl-muted)}.pagination{display:flex;justify-content:center;align-items:center;gap:18px;margin-top:28px}.cl-notice{display:block;margin-bottom:16px}@media(max-width:850px){.favorite-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:500px){.favorite-grid{grid-template-columns:1fr}.unavailable{min-height:220px}}
</style>
