<script setup>
import LoopButton from '../../components/LoopButton.vue'
import LoopLayout from '../../components/LoopLayout.vue'
const back = () => uni.switchTab({url:'/pages/profile/profile'})
</script>

<template>
  <LoopLayout>
    <view class="cl-page-heading"><text class="cl-title">我的收藏</text><text class="cl-subtitle">收藏关系应由服务端保存，并在不同会话中保持一致。</text></view>
    <view class="cl-notice">A-02 当前已合入本人列表、编辑与下架，但收藏列表及幂等添加/取消接口尚未实现。</view>
    <view class="cl-panel cl-empty"><text class="cl-empty-symbol">♡</text><text>收藏夹暂未开放</text><text class="cl-hint">这里不会读取公开列表拼出“收藏”，也不会把本地缓存冒充跨设备持久化。接口与唯一约束合入后再开放物品卡片和详情收藏操作。</text><LoopButton class="cl-btn" @click="back">返回个人页</LoopButton></view>
  </LoopLayout>
</template>
