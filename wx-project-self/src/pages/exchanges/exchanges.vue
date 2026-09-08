<script setup>
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
import { TOKEN_KEY } from '../../common/http'

const login = () => uni.navigateTo({url:'/pages/login/login?redirect=exchanges'})
const recommendations = () => uni.switchTab({url:'/pages/matches/matches'})
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的交换</text><text class="cl-subtitle">交换记录、允许动作和状态必须来自服务端。</text></view>
    <view v-if="!uni.getStorageSync(TOKEN_KEY)" class="cl-panel cl-empty"><text class="cl-empty-symbol">↗</text><text>登录后查看本人参与的交换</text><LoopButton class="cl-btn cl-btn--primary" @click="login">登录</LoopButton></view>
    <view v-else class="cl-panel dependency-panel"><view class="dependency-heading"><text class="dependency-symbol">↻</text><view><text class="cl-section-title">交换服务尚未开放</text><text class="cl-hint">B-03 的本人列表、详情与允许动作，以及 A-03 的事务重校验和幂等契约尚未实现。</text></view></view><view class="cl-notice"><text>当前后端交换写入口仍返回 501，也没有可调用的本人交换列表或详情接口。这里不会用推荐记录、本地缓存或组件夹具冒充真实交换。</text></view><view class="dependency-actions"><LoopButton class="cl-btn cl-btn--primary" @click="recommendations">查看只读推荐</LoopButton><LoopButton class="cl-btn" disabled>确认邀请 · 待 B-03</LoopButton><LoopButton class="cl-btn" disabled>取消交换 · 待 A/B</LoopButton></view><text class="cl-hint">依赖就绪后，本页将只渲染服务端返回的状态、版本与 allowedActions；刷新或冲突后重新读取详情，不在前端推导状态。</text></view>
  </LoopLayout>
</template>

<style scoped>
.dependency-panel{display:flex;flex-direction:column;gap:24px;max-width:760px}.dependency-heading{display:flex;align-items:center;gap:16px}.dependency-heading>view{display:flex;flex-direction:column;gap:7px}.dependency-symbol{display:flex;align-items:center;justify-content:center;width:54px;height:54px;border-radius:18px;background:var(--cl-primary-soft);color:var(--cl-primary);font-size:30px;flex-shrink:0}.dependency-actions{display:flex;flex-wrap:wrap;gap:10px}@media(max-width:520px){.dependency-panel{padding:22px}.dependency-actions{align-items:stretch;flex-direction:column}.dependency-actions .cl-btn{width:100%}}
</style>
