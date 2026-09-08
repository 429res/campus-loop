<script setup>
import LoopButton from '../../components/LoopButton.vue'
import { ref } from 'vue'
import { onLoad, onPullDownRefresh, onUnload } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { imageUrl, isAbortError, TOKEN_KEY } from '../../common/http'
import { favoriteIds, readAllFavorites, setFavorite } from '../../common/favorites.mjs'
import { itemIsAvailable, itemStatusLabel } from '../../common/items'
const item = ref(null), loading = ref(true), error = ref('')
const favorited = ref(false), favoriteBusy = ref(false), favoriteError = ref('')
let itemId = ''
let activeRequest
async function load() {
  activeRequest?.abort?.(); loading.value = true; error.value = ''
  if (!/^\d+$/.test(itemId) || Number(itemId) < 1) { error.value = '物品链接无效'; loading.value = false; return }
  try {
    activeRequest = http.get(`/api/items/${itemId}`,{}, {silent:true}); item.value = await activeRequest
    await loadFavorite()
  }
  catch(e) { if (!isAbortError(e)) { item.value = null; error.value = e.message } }
  finally { loading.value = false }
}
async function loadFavorite() {
  const token = uni.getStorageSync(TOKEN_KEY)
  favoriteError.value = ''
  if (!token) { favorited.value = false; return }
  try { const value = favoriteIds(await readAllFavorites({silent:true})).has(String(itemId)); if (token === uni.getStorageSync(TOKEN_KEY)) favorited.value = value }
  catch (e) { if (token === uni.getStorageSync(TOKEN_KEY) && !isAbortError(e)) favoriteError.value = e.message }
}
async function toggleFavorite() {
  const token = uni.getStorageSync(TOKEN_KEY)
  if (!token) { uni.navigateTo({url:'/pages/login/login'}); return }
  if (favoriteBusy.value) return
  favoriteBusy.value = true; favoriteError.value = ''
  const next = !favorited.value
  try { await setFavorite(itemId,next); if (token === uni.getStorageSync(TOKEN_KEY)) favorited.value = next }
  catch (e) {
    if (token !== uni.getStorageSync(TOKEN_KEY)) return
    favoriteError.value = e.uncertain ? '收藏操作结果无法确认，已重新读取收藏状态。' : e.message
    if (e.uncertain) await loadFavorite()
    if (e.status === 404 && next) await load()
  } finally { favoriteBusy.value = false }
}
onLoad(options => {itemId = String(options.id || ''); load()})
onPullDownRefresh(async () => { await load(); uni.stopPullDownRefresh() })
onUnload(() => activeRequest?.abort?.())
const matches = () => uni.switchTab({url:'/pages/matches/matches'})
const back = () => uni.switchTab({url:'/pages/home/home'})
</script>
<template>
  <LoopLayout><LoopButton class="cl-btn cl-btn--quiet back" @click="back">← 返回发现</LoopButton><view v-if="loading" class="cl-empty"><text class="cl-label">正在读取物品…</text></view><view v-else-if="error" class="cl-panel cl-empty" role="alert"><text>{{ error }}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton></view><view v-else-if="item" class="detail-grid"><view class="detail-image"><image :src="imageUrl(item.imageUrl,item.title)" mode="aspectFit"/></view><view class="cl-panel detail-info"><view class="cl-row"><text class="cl-tag">{{ item.categoryName }}</text><text class="cl-tag cl-tag--pink">{{ ['','有使用痕迹','正常使用','成色良好','几乎全新','全新未用'][item.conditionLevel] }}</text></view><text class="cl-title detail-title">{{ item.title }}</text><view class="detail-owner"><text class="cl-avatar">{{ (item.ownerName || '同学').slice(0,1) }}</text><view><text class="owner-name">{{ item.ownerName }}</text><text class="cl-hint">发布于 {{ item.createdAt?.slice(0,10) || '校园' }}</text></view><text class="cl-tag" :class="itemIsAvailable(item.status) ? 'cl-tag--pink' : 'cl-tag--muted'">{{ itemStatusLabel(item.status) }}</text></view><view class="cl-divider"/><text class="cl-field-title">关于这件物品</text><text class="detail-description">{{ item.description }}</text><view class="detail-tags"><text v-for="tag in item.tags" :key="tag" class="cl-tag cl-tag--muted"># {{ tag }}</text></view><view class="wanted-box"><text class="cl-label">这位同学想换到</text><text class="wanted-title">{{ item.wantedCategoryName }}</text><text class="cl-hint">{{ item.wantedTags?.length ? `偏好：${item.wantedTags.join(' · ')}` : '没有额外标签偏好' }}</text></view><view class="detail-actions"><LoopButton class="cl-btn cl-btn--primary" :disabled="!itemIsAvailable(item.status)" @click="matches">{{ itemIsAvailable(item.status) ? '看看交换灵感 ↗' : `${itemStatusLabel(item.status)}，暂不可参与推荐` }}</LoopButton><LoopButton class="cl-btn" :disabled="favoriteBusy" @click="toggleFavorite">{{ favoriteBusy ? '处理中…' : favorited ? '♥ 已收藏，点击取消' : '♡ 收藏' }}</LoopButton></view><text v-if="favoriteError" class="cl-error detail-pending" role="alert">{{ favoriteError }}</text><text class="cl-hint detail-pending">收藏不等于占用或可交换保证；物品状态始终以服务端为准。</text></view></view></LoopLayout>
</template>
<style scoped>
.back{margin:5px 0 20px;padding-left:0}.detail-grid{display:grid;grid-template-columns:1.15fr 1fr;gap:30px;align-items:start}.detail-image{background:var(--cl-surface);border:1px solid var(--cl-border);border-radius:22px;overflow:hidden;position:sticky;top:20px}.detail-image image{width:100%;height:520px}.detail-info{padding:32px}.detail-title{margin:20px 0}.detail-owner{display:flex;gap:12px;align-items:center}.detail-owner>.cl-avatar{width:40px;height:40px;font-size:17px}.detail-owner>view{display:flex;flex-direction:column;gap:3px;flex:1}.owner-name{font-size:14px;font-weight:600}.detail-description{white-space:pre-wrap;overflow-wrap:anywhere;display:block;font-size:14px;line-height:1.9;color:var(--cl-muted);margin:13px 0}.detail-tags{display:flex;gap:6px;flex-wrap:wrap}.wanted-box{padding:20px;background:var(--cl-primary-soft);border-radius:14px;margin:26px 0}.wanted-title{display:block;font-size:20px;color:var(--cl-primary);font-weight:700;margin:8px 0}.detail-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.detail-actions>.cl-btn{width:100%}.detail-pending{display:block;margin-top:16px}@media(max-width:750px){.detail-grid{grid-template-columns:1fr;gap:20px}.detail-image{position:static}.detail-image image{height:300px}.detail-info{padding:24px}.detail-title{font-size:24px}}@media(max-width:430px){.detail-actions{grid-template-columns:1fr}}
</style>
