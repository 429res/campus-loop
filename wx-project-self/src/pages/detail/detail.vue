<script setup>
import MemberLink from '../../components/MemberLink.vue'
import DirectExchange from '../../components/DirectExchange.vue'
import LoopSkeleton from '../../components/LoopSkeleton.vue'
import LoopIcon from '../../components/LoopIcon.vue'
import LoopButton from '../../components/LoopButton.vue'
import { ref } from 'vue'
import { onLoad, onShow, onPullDownRefresh, onUnload } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import http, { imageUrl, isAbortError, TOKEN_KEY } from '../../common/http'
import { favoriteIds, readAllFavorites, setFavorite } from '../../common/favorites.mjs'
import { itemIsAvailable, itemStatusLabel } from '../../common/items'
const item = ref(null), loading = ref(true), error = ref('')
const favorited = ref(false), favoriteBusy = ref(false), favoriteError = ref('')
const publicStates = new Set(['AVAILABLE', 'RESERVED', 'EXCHANGED'])
let itemId = '', mine = false, sequence = 0
let activeRequest
async function load() {
  const current = ++sequence, token = uni.getStorageSync(TOKEN_KEY)
  activeRequest?.abort?.(); item.value = null; favorited.value = false; favoriteError.value = ''; loading.value = true; error.value = ''
  if (!/^[1-9][0-9]*$/.test(itemId)) { error.value = '物品链接无效'; loading.value = false; return }
  try {
    activeRequest = http.get(`/api/items/${mine ? 'mine/' : ''}${itemId}`,{}, {silent:true})
    const loaded = await activeRequest
    if (current !== sequence || (mine && token !== uni.getStorageSync(TOKEN_KEY))) return
    item.value = loaded
    await loadFavorite(current)
  } catch(e) { if (current === sequence && !isAbortError(e)) error.value = e.message }
  finally { if (current === sequence) loading.value = false }
}
async function loadFavorite(current = sequence) {
  const token = uni.getStorageSync(TOKEN_KEY)
  if (current !== sequence) return
  favoriteError.value = ''
  if (!token) { favorited.value = false; return }
  try {
    const value = favoriteIds(await readAllFavorites({silent:true})).has(String(itemId))
    if (current === sequence && token === uni.getStorageSync(TOKEN_KEY)) favorited.value = value
  } catch(e) {
    if (current === sequence && token === uni.getStorageSync(TOKEN_KEY) && !isAbortError(e)) favoriteError.value = e.message
  }
}
async function toggleFavorite() {
  const token = uni.getStorageSync(TOKEN_KEY), current = sequence
  if (!token) { uni.navigateTo({url:'/pages/login/login'}); return }
  if (favoriteBusy.value || !item.value || (!favorited.value && !publicStates.has(item.value.status))) return
  favoriteBusy.value = true; favoriteError.value = ''
  const next = !favorited.value
  try {
    await setFavorite(itemId,next)
    if (current === sequence && token === uni.getStorageSync(TOKEN_KEY)) favorited.value = next
  } catch(e) {
    if (current !== sequence || token !== uni.getStorageSync(TOKEN_KEY)) return
    if (e.uncertain) {
      await loadFavorite(current)
      if (current === sequence && token === uni.getStorageSync(TOKEN_KEY) && !favoriteError.value) favoriteError.value = '收藏操作结果无法确认，已重新读取收藏状态。'
    } else favoriteError.value = e.message
    if (e.status === 404 && next) await load()
  } finally { favoriteBusy.value = false }
}
onLoad(options => {itemId = String(options.id || ''); mine = options.mine === '1'})
onShow(load)
onPullDownRefresh(async () => { await load(); uni.stopPullDownRefresh() })
onUnload(() => {sequence++; activeRequest?.abort?.(); item.value = null; favorited.value = false})
const mineList = () => uni.navigateTo({url:'/pages/my-items/my-items'})
const history = () => uni.navigateTo({url:`/pages/history/history?id=${itemId}&title=${encodeURIComponent(item.value?.title || '物品')}`})
const report = () => uni.navigateTo({url:`/pages/governance/governance?type=ITEM&id=${itemId}&title=${encodeURIComponent(item.value?.title || '')}`})
function previewGallery(index){const urls=(item.value.imageUrls?.length?item.value.imageUrls:[item.value.imageUrl]).filter(Boolean).map(u=>imageUrl(u));if(urls.length)uni.previewImage({urls,current:index})}
</script>
<template>
  <LoopLayout><LoopSkeleton v-if="loading" /><view v-else-if="error" class="cl-panel cl-empty" role="alert"><text>{{ error }}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton></view><view v-else-if="item" class="detail-grid"><view class="detail-image"><swiper v-if="item.imageUrls?.length>1" class="item-gallery" indicator-dots circular><swiper-item v-for="(url,index) in item.imageUrls" :key="url"><image :src="imageUrl(url)" mode="aspectFit" @click="previewGallery(index)"/></swiper-item></swiper><image v-else :src="imageUrl(item.imageUrl,item.title)" mode="aspectFit" @click="previewGallery(0)"/><text v-if="item.imageUrls?.length>1" class="gallery-count">{{item.imageUrls.length}} 张照片 · 左右滑动查看</text></view><view class="cl-panel detail-info"><view class="cl-row"><text class="cl-tag">{{ item.categoryName }}</text><text class="cl-tag cl-tag--pink">{{ ['','有使用痕迹','正常使用','成色良好','几乎全新','全新未用'][item.conditionLevel] }}</text></view><text class="cl-title detail-title">{{ item.title }}</text><view class="detail-owner"><MemberLink :id="item.ownerId" :name="item.ownerName" :avatar="item.ownerAvatarUrl" :show-name="false" :size="42"/><view><MemberLink :id="item.ownerId" :name="item.ownerName" :avatar="item.ownerAvatarUrl" :show-avatar="false"/><text class="cl-hint">发布于 {{ item.createdAt?.slice(0,10) || '校园' }}</text></view><text class="cl-tag" :class="itemIsAvailable(item.status) ? 'cl-tag--pink' : 'cl-tag--muted'">{{ itemStatusLabel(item.status) }}</text></view><view v-if="mine" class="cl-notice review-notice"><text v-if="['PENDING_REVIEW','REJECTED'].includes(item.status)">当前内容仅本人和管理员可见，不参与推荐。</text><text v-if="item.reviewBasis === 'LEGACY_DIRECT'">历史直发记录，未经过管理员审核。</text><text v-if="item.reviewDecision">最近一次{{ item.reviewDecision === 'REJECT' ? '驳回' : '通过' }}（审核版本 {{ item.reviewedVersion }}，当前版本 {{ item.version }}）：{{ item.reviewReason }}</text><text v-else>暂无审核决定。</text><LoopButton class="cl-btn" @click="mineList">查看我的物品与审核进度</LoopButton></view><view class="cl-divider"/><text class="cl-field-title">关于这件物品</text><text class="detail-description">{{ item.description }}</text><view class="detail-tags"><text v-for="tag in item.tags" :key="tag" class="cl-tag cl-tag--muted"># {{ tag }}</text></view><view class="wanted-box"><text class="cl-label">这位同学想换到</text><text class="wanted-title">{{ item.wantedCategoryName }}</text><text class="cl-hint">{{ item.wantedTags?.length ? `偏好：${item.wantedTags.join(' · ')}` : '没有额外标签偏好' }}</text></view><view class="detail-actions"><DirectExchange :item="item"/><LoopButton class="cl-btn" :disabled="favoriteBusy || (!favorited && !publicStates.has(item.status))" @click="toggleFavorite"><LoopIcon name="heart" :tone="favorited ? 'primary' : 'default'" :size="20"/>{{ favoriteBusy ? '处理中…' : favorited ? '已收藏，点击取消' : '收藏' }}</LoopButton><LoopButton class="cl-btn" @click="history">查看履历与来源</LoopButton><LoopButton class="cl-btn" @click="report">举报此物品</LoopButton></view><text v-if="!publicStates.has(item.status)" class="cl-hint detail-pending">非公开物品不能新增收藏；已收藏关系可取消。</text><text v-if="favoriteError" class="cl-error detail-pending" role="alert">{{ favoriteError }}</text><text class="cl-hint detail-pending">收藏不等于占用或可交换保证；物品状态始终以服务端为准。</text></view></view></LoopLayout>
</template>
<style scoped>
.review-notice{display:flex;flex-direction:column;gap:10px;margin-top:20px;white-space:pre-wrap;overflow-wrap:anywhere}
.back{margin:5px 0 20px;padding-left:0}.detail-grid{display:grid;grid-template-columns:1.15fr 1fr;gap:30px;align-items:start}.detail-image{background:var(--cl-surface);border:1px solid var(--cl-border);border-radius:22px;overflow:hidden;position:sticky;top:20px}.detail-image image{width:100%;height:520px}.detail-info{padding:32px}.detail-title{margin:20px 0}.detail-owner{display:flex;gap:12px;align-items:center}.detail-owner>.cl-avatar{width:40px;height:40px;font-size:17px}.detail-owner>view{display:flex;flex-direction:column;gap:3px;flex:1}.owner-name{font-size:14px;font-weight:600}.detail-description{white-space:pre-wrap;overflow-wrap:anywhere;display:block;font-size:14px;line-height:1.9;color:var(--cl-muted);margin:13px 0}.detail-tags{display:flex;gap:6px;flex-wrap:wrap}.wanted-box{padding:20px;background:var(--cl-primary-soft);border-radius:14px;margin:26px 0}.wanted-title{display:block;font-size:20px;color:var(--cl-primary);font-weight:700;margin:8px 0}.detail-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.detail-actions>.cl-btn{width:100%}.detail-pending{display:block;margin-top:16px}@media(max-width:750px){.detail-grid{grid-template-columns:1fr;gap:20px}.detail-image{position:static}.detail-image image{height:300px}.detail-info{padding:24px}.detail-title{font-size:24px}}@media(max-width:430px){.detail-actions{grid-template-columns:1fr}}
.item-gallery{height:520px}.gallery-count{display:block;text-align:center;padding:10px;color:var(--cl-muted);font-size:12px}@media(max-width:750px){.item-gallery{height:300px}}
</style>
