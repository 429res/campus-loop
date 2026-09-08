<script setup>
import { ref } from 'vue'
import { onShow, onUnload, onPullDownRefresh } from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopButton from '../../components/LoopButton.vue'
import LoopPicker from '../../components/LoopPicker.vue'
import http, { TOKEN_KEY, isAbortError } from '../../common/http'
import { itemStatusLabel } from '../../common/items'
const states = ['', 'PENDING_REVIEW', 'REJECTED', 'AVAILABLE', 'RESERVED', 'EXCHANGED', 'HIDDEN', 'DRAFT']
const labels = states.map(s => s ? itemStatusLabel(s) : '全部状态')
const rows = ref([]), total = ref(0), page = ref(1), selected = ref(0), busy = ref(false), error = ref('')
let request, sequence = 0, loadedToken = ''
async function load() {
  const current = ++sequence, token = uni.getStorageSync(TOKEN_KEY)
  request?.abort?.(); rows.value = []; total.value = 0; error.value = ''; busy.value = true
  if (loadedToken !== token) { page.value = 1; loadedToken = token }
  try {
    request = http.get('/api/items/mine',{page:page.value,size:12,status:states[selected.value]}, {silent:true})
    const data = await request
    if (current !== sequence || token !== uni.getStorageSync(TOKEN_KEY)) return
    rows.value = data.records; total.value = data.total
  } catch (e) { if (current === sequence && !isAbortError(e)) error.value = e.message }
  finally { if (current === sequence) busy.value = false }
}
function select(event) { selected.value = Number(event.detail.value); page.value = 1; load() }
function turn(delta) { if (!busy.value) {page.value += delta; load()} }
const detail = item => uni.navigateTo({url:`/pages/detail/detail?id=${item.id}&mine=1`})
const login = () => uni.navigateTo({url:'/pages/login/login'})
onShow(load)
onPullDownRefresh(async () => {await load(); uni.stopPullDownRefresh()})
onUnload(() => {sequence++; request?.abort?.(); rows.value = []})
</script>
<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的物品</text><text class="cl-subtitle">查看提交内容与审核进度。待审和驳回内容仅本人及管理员可见。</text></view>
    <view class="cl-panel cl-stack">
      <LoopPicker :range="labels" :value="selected" :disabled="busy" aria-label="我的物品状态" @change="select"><view class="cl-picker">{{ labels[selected] }} ⌄</view></LoopPicker>
      <text v-if="busy" class="cl-hint" role="status">正在读取…</text>
      <view v-else-if="error" class="cl-empty" role="alert"><text>{{ error }}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton><LoopButton class="cl-btn" @click="login">登录</LoopButton></view>
      <template v-else>
        <view v-if="!rows.length" class="cl-empty">当前条件下没有物品</view>
        <view v-for="item in rows" :key="item.id" class="own-item">
          <view class="cl-row"><text class="cl-field-title">{{ item.title }}</text><text class="cl-tag">{{ itemStatusLabel(item.status) }}</text></view>
          <text class="cl-hint">{{ item.categoryName }} · {{ item.createdAt?.slice(0,10) }}</text>
          <text v-if="item.reviewDecision" class="review-reason">最近一次{{ item.reviewDecision === 'REJECT' ? '驳回' : '通过' }}（版本 {{ item.reviewedVersion }}）：{{ item.reviewReason }}</text>
          <LoopButton class="cl-btn" @click="detail(item)">查看本人详情</LoopButton>
        </view>
      </template>
      <view class="cl-row"><LoopButton class="cl-btn" :disabled="busy || page <= 1" @click="turn(-1)">上一页</LoopButton><text>{{ page }} · 共 {{ total }} 件</text><LoopButton class="cl-btn" :disabled="busy || page * 12 >= total" @click="turn(1)">下一页</LoopButton></view>
    </view>
  </LoopLayout>
</template>
<style scoped>
.own-item{display:flex;flex-direction:column;gap:12px;padding:20px 0;border-bottom:1px solid var(--cl-border)}.own-item .cl-btn{align-self:flex-start}.review-reason{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.7}
</style>
