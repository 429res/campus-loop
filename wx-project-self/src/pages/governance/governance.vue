<script setup>
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import { TOKEN_KEY } from '../../common/http'

const targetType = ref(''), targetId = ref(''), targetTitle = ref('')
const sessionToken = ref('')
const loggedIn = computed(() => !!sessionToken.value)
const targetText = computed(() => targetId.value ? `${targetType.value === 'EXCHANGE' ? '交换' : '物品'} #${targetId.value}${targetTitle.value ? ` · ${targetTitle.value}` : ''}` : '尚未选择目标')
onLoad(options => {
  if (['ITEM','EXCHANGE'].includes(options.type)) targetType.value = options.type
  if (/^[1-9][0-9]*$/.test(options.id || '')) targetId.value = String(options.id)
  targetTitle.value = decodeURIComponent(options.title || '')
})
onShow(() => { sessionToken.value = uni.getStorageSync(TOKEN_KEY) || '' })
const login = () => uni.navigateTo({url:'/pages/login/login'})
const exchanges = () => uni.navigateTo({url:'/pages/exchanges/exchanges'})
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">举报与争议</text><text class="cl-subtitle">两类流程含义不同；只展示服务端已经具备的能力。</text></view>
    <view v-if="!loggedIn" class="cl-panel cl-empty"><text>登录后查看可用入口</text><LoopButton class="cl-btn cl-btn--primary" @click="login">重新登录</LoopButton></view>
    <view v-else class="governance-grid">
      <view class="cl-panel section"><view class="section-title"><text class="cl-section-title">举报内容或账号</text><text class="cl-tag cl-tag--muted">待 A/C 接口</text></view><text class="cl-hint">目标：{{ targetText }}</text><text class="cl-hint">提交状态：未提交（服务端接口未实现）</text><view class="cl-notice">通用举报用于向平台提交目标、原因和独立证据，不等于交换争议，也不承诺自动下架、释放占用、退款或改变所有权。</view><view class="cl-field"><text class="cl-field-title">举报原因</text><textarea class="cl-textarea" disabled placeholder="POST /api/reports 尚未实现，当前不收集或缓存输入"/></view><view class="cl-field"><text class="cl-field-title">举报证据</text><LoopButton class="cl-btn" disabled>选择私有证据 · 待开发</LoopButton></view><LoopButton class="cl-btn cl-btn--primary" disabled>提交举报 · 待开发</LoopButton><text class="cl-hint">本人举报列表、处理状态和安全目标摘要接口同样未实现，因此本页不生成编号或本地“成功”记录。</text></view>
      <view class="cl-panel section"><view class="section-title"><text class="cl-section-title">实物交接争议</text><text class="cl-tag cl-tag--pink">参与者入口已接入</text></view><view class="cl-notice">争议只适用于 READY 且已经开始交接的本人交换。登记后交换进入 DISPUTED，并保留当前所有权、需求和占用；管理员裁决仍待开发。</view><text class="cl-hint">原因和状态由交换详情回读；无附件契约，不会借用履历私有证据。</text><LoopButton class="cl-btn cl-btn--primary" @click="exchanges">前往我的交换登记或查询</LoopButton></view>
    </view>
  </LoopLayout>
</template>

<style scoped>
.governance-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.section{display:flex;flex-direction:column;gap:18px}.section-title{display:flex;align-items:center;justify-content:space-between;gap:12px}.cl-textarea[disabled]{opacity:var(--cl-disabled-opacity)}@media(max-width:760px){.governance-grid{grid-template-columns:1fr}.section{padding:22px}}
</style>
