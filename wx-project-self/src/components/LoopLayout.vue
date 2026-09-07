<script setup>
import LoopButton from './LoopButton.vue'
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { useTheme } from '../composables/useTheme'
const { theme, toggleTheme, apply, reducedMotion } = useTheme()
const routes = ['home','matches','publish','profile']
const active = ref(0)
onShow(() => {apply();const pages=getCurrentPages();active.value=routes.findIndex(route=>pages[pages.length-1]?.route===`pages/${route}/${route}`)})
const navigate = index => uni.switchTab({url:`/pages/${routes[index]}/${routes[index]}`})
const goHome = () => uni.switchTab({ url:'/pages/home/home' })
const goPublish = () => uni.switchTab({url:'/pages/publish/publish'})
</script>
<template>
  <view class="cl-app" :class="{ 'theme-dark': theme === 'dark', 'cl-reduce-motion': reducedMotion }">
    <view class="cl-shell">
      <view class="cl-top">
        <LoopButton class="cl-brand cl-btn--quiet" aria-label="Campus Loop 首页" @click="goHome"><text class="cl-brand-mark">↻</text><text>Campus Loop</text><text class="cl-brand-note">让闲置，遇见需要</text></LoopButton>
        <view class="cl-top-actions">
          <LoopButton class="cl-icon-btn" :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme">{{ theme === 'dark' ? '☀' : '☾' }}</LoopButton>
          <LoopButton class="cl-btn cl-btn--primary cl-desktop-only" @click="goPublish">＋ 发布闲置</LoopButton>
        </view>
      </view>
      <slot />
      <text class="cl-footer-note">CAMPUS LOOP · 校园闲置循环计划</text>
    </view>
    <!-- #ifdef H5 -->
    <view v-if="active>=0" class="loop-nav cl-glass" role="navigation" aria-label="用户端导航"><view class="nav-indicator" :style="{transform:`translateX(${active * 100}%)`}"/><LoopButton v-for="(name,index) in ['发现','交换灵感','发布','我的']" :key="name" class="nav-button" :class="{selected:active===index}" :aria-current="active===index ? 'page' : undefined" @click="navigate(index)"><text class="nav-symbol">{{ ['⌂','↻','＋','◉'][index] }}</text><text>{{ name }}</text></LoopButton></view>
    <!-- #endif -->
  </view>
</template>
<style scoped>
.loop-nav{position:fixed;z-index:50;bottom:0;left:0;right:0;padding:6px 12px calc(6px + env(safe-area-inset-bottom));display:flex;border-radius:0;isolation:isolate}.nav-button{flex:1;padding:4px 8px;background:transparent;color:var(--cl-muted);font-size:10px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;min-height:44px}.nav-symbol{font-size:23px;line-height:1.1}.nav-button.selected{color:var(--cl-primary);font-weight:650}.nav-indicator{position:absolute;z-index:-1;width:calc((100% - 24px)/4);left:12px;top:6px;bottom:calc(6px + env(safe-area-inset-bottom));border-radius:12px;background:var(--cl-primary-soft);transition:transform var(--cl-motion-normal) var(--cl-ease)}
</style>
