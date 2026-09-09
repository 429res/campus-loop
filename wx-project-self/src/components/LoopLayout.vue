<script setup>
import {unreadCount,notificationToast,startNotifications,stopNotifications,dismissNotification,openNotification} from '../common/notification-center.js'
import { platformNavigation } from '../common/platform-adapters.js'
import LoopIcon from './LoopIcon.vue'
import LoopButton from './LoopButton.vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { computed, ref, nextTick, onMounted, onUnmounted } from 'vue'
import {backWithinApp} from '../common/navigation.mjs'
import { useTheme } from '../composables/useTheme'
const { theme, toggleTheme, apply, reducedMotion } = useTheme()
const routes = ['home','matches','publish','profile']
const props = defineProps({ activeTab: { type: String, default: '' }, backTo: { type: String, default: 'home' } })
const active = computed(() => routes.indexOf(props.activeTab))
const entering = ref(true)
const notificationOwner=Symbol()
let pageVisible=false
const resumeNotifications=()=>{
 // #ifdef H5
 if(document.hidden)return
 // #endif
 if(pageVisible)startNotifications(notificationOwner)
}
onShow(()=>{pageVisible=true;resumeNotifications()});onHide(()=>{pageVisible=false;stopNotifications(notificationOwner)});onUnload(()=>{pageVisible=false;stopNotifications(notificationOwner)})
// #ifdef H5
const visibilityChanged=()=>document.hidden?stopNotifications(notificationOwner):resumeNotifications()
onMounted(()=>document.addEventListener('visibilitychange',visibilityChanged))
onUnmounted(()=>document.removeEventListener('visibilitychange',visibilityChanged))
// #endif
const inbox=()=>uni.navigateTo({url:'/pages/notifications/notifications'})
onShow(async () => { apply(); entering.value=false; await nextTick(); entering.value=true })
const navigate = index => uni.switchTab({url:`/pages/${routes[index]}/${routes[index]}`})
const goHome = () => uni.switchTab({ url:'/pages/home/home' })
const goBack=()=>backWithinApp(platformNavigation,typeof getCurrentPages==='function'?getCurrentPages():[],props.backTo)
const goPublish = () => uni.switchTab({url:'/pages/publish/publish'})
</script>
<template>
  <view class="cl-app" :class="{ 'theme-dark': theme === 'dark', 'cl-reduce-motion': reducedMotion }">
    <view class="cl-shell">
      <view class="cl-top">
        <LoopButton class="cl-brand cl-btn--quiet" aria-label="Campus Loop 首页" @click="goHome"><image class="cl-brand-mark" src="/static/brand-mark.png" mode="aspectFit" aria-hidden="true"/><text class="cl-brand-name">Campus <text class="cl-brand-accent">Loop</text></text><text class="cl-brand-note">让闲置，继续有用</text></LoopButton>
        <view class="cl-top-actions"><LoopButton class="cl-icon-btn notification-bell" :aria-label="`消息通知${unreadCount?'，'+unreadCount+'条未读':''}`" @click="inbox"><LoopIcon name="bell"/><text v-if="unreadCount" class="notification-badge">{{unreadCount>99?'99+':unreadCount}}</text></LoopButton>
          <LoopButton class="cl-icon-btn" :aria-label="theme === 'dark' ? '切换浅色模式' : '切换深色模式'" @click="toggleTheme"><LoopIcon :name="theme === 'dark' ? 'sun' : 'moon'"/></LoopButton>
          <LoopButton class="cl-btn cl-btn--primary cl-desktop-only" @click="goPublish"><LoopIcon name="plus" tone="white" :size="18"/>发布闲置</LoopButton>
        </view>
      </view>
      <view v-if="!activeTab" class="page-back"><LoopButton class="cl-btn cl-btn--quiet" aria-label="返回上一页" @click="goBack">‹ 返回</LoopButton></view>
      <view class="page-content" :class="{entering}"><slot /></view>
      <text class="cl-footer-note">CAMPUS LOOP · 校园闲置循环计划</text>
    </view>
    <view v-if="notificationToast" class="notification-toast cl-panel" role="status" aria-live="polite"><LoopIcon name="bell" tone="primary" :size="26"/><LoopButton class="notification-content" @click="openNotification(notificationToast)"><text class="cl-field-title">{{notificationToast.title}}</text><text class="cl-hint">{{notificationToast.body}}</text><text class="notification-link">查看消息 · {{notificationToast.count}} 条未读 ›</text></LoopButton><LoopButton class="cl-icon-btn" aria-label="关闭消息提醒" @click="dismissNotification"><LoopIcon name="close" :size="18"/></LoopButton></view>
    <!-- #ifdef H5 -->
    <view v-if="active>=0" class="loop-nav cl-glass" role="navigation" aria-label="用户端导航"><view class="nav-indicator" :style="{transform:`translateX(${active * 100}%)`}"/><LoopButton v-for="(name,index) in ['发现','交换灵感','发布','我的']" :key="name" class="nav-button" :class="{selected:active===index}" :aria-current="active===index ? 'page' : undefined" @click="navigate(index)"><LoopIcon :name="['home','exchange','plus','user'][index]" :tone="active===index ? 'primary' : 'default'"/><text>{{ name }}</text></LoopButton></view>
    <!-- #endif -->
  </view>
</template>
<style scoped>
.page-back{margin:4px 0 18px}.page-back .cl-btn{color:var(--cl-text)}
.loop-nav{position:fixed;z-index:50;bottom:0;left:0;right:0;padding:6px 12px calc(6px + env(safe-area-inset-bottom));display:flex;border-radius:0;isolation:isolate}.nav-button{flex:1;padding:4px 8px;background:transparent;color:var(--cl-muted);font-size:10px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;min-height:44px}.nav-symbol{font-size:23px;line-height:1.1}.nav-button.selected{color:var(--cl-primary);font-weight:650}.nav-indicator{position:absolute;z-index:-1;width:calc((100% - 24px)/4);left:12px;top:6px;bottom:calc(6px + env(safe-area-inset-bottom));border-radius:12px;background:var(--cl-primary-soft);transition:transform var(--cl-motion-normal) var(--cl-ease)}
/* The outer dock owns the glass. The active pill and icons are flat. */
.loop-nav{width:min(520px,calc(100% - 32px));left:50%;right:auto;bottom:calc(12px + env(safe-area-inset-bottom));transform:translateX(-50%);padding:6px;border-radius:22px;gap:0}
.nav-button{min-width:0;min-height:52px;gap:5px;padding:6px 2px;font-size:11px;border-radius:16px;line-height:1.2;position:relative;z-index:1;white-space:nowrap}
.nav-indicator{z-index:0;left:6px;top:6px;bottom:6px;width:calc((100% - 12px)/4);border-radius:16px;pointer-events:none}

.notification-bell{position:relative}.notification-badge{position:absolute;top:-5px;right:-5px;min-width:19px;height:19px;padding:0 4px;display:flex;align-items:center;justify-content:center;box-sizing:border-box;background:var(--cl-primary);color:white;border:2px solid var(--cl-surface);border-radius:12px;font-size:10px;font-weight:700}.notification-toast{position:fixed;top:90px;right:max(20px,calc((100vw - 1280px)/2));width:min(390px,calc(100vw - 32px));box-sizing:border-box;padding:18px;display:flex;align-items:flex-start;gap:12px;z-index:85;box-shadow:0 12px 40px #0002;border:1px solid var(--cl-primary);animation:page-arrive .25s var(--cl-ease)}.notification-content{display:flex;flex:1;min-width:0;flex-direction:column;gap:8px;padding:0;text-align:left;background:transparent;color:var(--cl-text)}.notification-content .cl-hint{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;line-height:1.7}.notification-link{font-size:12px;color:var(--cl-primary)}.notification-toast>.cl-icon-btn{width:30px;height:30px;min-height:30px;padding:0;flex:none}@media(max-width:600px){.notification-toast{top:78px;right:16px}}
</style>
