<script setup>
import LoopIcon from './LoopIcon.vue'
import LoopButton from './LoopButton.vue'
import { onShow } from '@dcloudio/uni-app'
import { computed } from 'vue'
import { useTheme } from '../composables/useTheme'
const { theme, toggleTheme, apply, reducedMotion } = useTheme()
const routes = ['home','matches','publish','profile']
const props = defineProps({ activeTab: { type: String, default: '' } })
const active = computed(() => routes.indexOf(props.activeTab))
onShow(apply)
const navigate = index => uni.switchTab({url:`/pages/${routes[index]}/${routes[index]}`})
const goHome = () => uni.switchTab({ url:'/pages/home/home' })
const goPublish = () => uni.switchTab({url:'/pages/publish/publish'})
</script>
<template>
  <view class="cl-app" :class="{ 'theme-dark': theme === 'dark', 'cl-reduce-motion': reducedMotion }">
    <view class="cl-shell">
      <view class="cl-top">
        <LoopButton class="cl-brand cl-btn--quiet" aria-label="Campus Loop 首页" @click="goHome"><image class="cl-brand-mark" src="/static/brand-mark.png" mode="aspectFit" aria-hidden="true"/><text class="cl-brand-name">Campus <text class="cl-brand-accent">Loop</text></text><text class="cl-brand-note">让闲置，继续有用</text></LoopButton>
        <view class="cl-top-actions">
          <LoopButton class="cl-icon-btn" :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme"><LoopIcon :name="theme === 'dark' ? 'sun' : 'moon'"/></LoopButton>
          <LoopButton class="cl-btn cl-btn--primary cl-desktop-only" @click="goPublish"><LoopIcon name="plus" tone="white" :size="18"/>发布闲置</LoopButton>
        </view>
      </view>
      <slot />
      <text class="cl-footer-note">CAMPUS LOOP · 校园闲置循环计划</text>
    </view>
    <!-- #ifdef H5 -->
    <view v-if="active>=0" class="loop-nav cl-glass" role="navigation" aria-label="用户端导航"><view class="nav-indicator" :style="{transform:`translateX(${active * 100}%)`}"/><LoopButton v-for="(name,index) in ['发现','交换灵感','发布','我的']" :key="name" class="nav-button" :class="{selected:active===index}" :aria-current="active===index ? 'page' : undefined" @click="navigate(index)"><LoopIcon :name="['home','exchange','plus','user'][index]" :tone="active===index ? 'primary' : 'default'"/><text>{{ name }}</text></LoopButton></view>
    <!-- #endif -->
  </view>
</template>
<style scoped>
.loop-nav{position:fixed;z-index:50;bottom:0;left:0;right:0;padding:6px 12px calc(6px + env(safe-area-inset-bottom));display:flex;border-radius:0;isolation:isolate}.nav-button{flex:1;padding:4px 8px;background:transparent;color:var(--cl-muted);font-size:10px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;min-height:44px}.nav-symbol{font-size:23px;line-height:1.1}.nav-button.selected{color:var(--cl-primary);font-weight:650}.nav-indicator{position:absolute;z-index:-1;width:calc((100% - 24px)/4);left:12px;top:6px;bottom:calc(6px + env(safe-area-inset-bottom));border-radius:12px;background:var(--cl-primary-soft);transition:transform var(--cl-motion-normal) var(--cl-ease)}
/* The outer dock owns the glass. The active pill and icons are flat. */
.loop-nav{width:min(520px,calc(100% - 32px));left:50%;right:auto;bottom:calc(12px + env(safe-area-inset-bottom));transform:translateX(-50%);padding:6px;border-radius:22px;gap:0}
.nav-button{min-width:0;min-height:52px;gap:5px;padding:6px 2px;font-size:11px;border-radius:16px;line-height:1.2;position:relative;z-index:1;white-space:nowrap}
.nav-indicator{z-index:0;left:6px;top:6px;bottom:6px;width:calc((100% - 12px)/4);border-radius:16px;pointer-events:none}

</style>
