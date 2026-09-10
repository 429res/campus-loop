<script setup>
import {imageUrl} from '../common/http'
import LoopIcon from './LoopIcon.vue'
defineProps({url:String})
function preview(url){if(url)uni.previewImage({urls:[imageUrl(url)]})}
</script>
<template><view class="profile-cover" :class="{'has-cover':url}"><image v-if="url" :src="imageUrl(url)" mode="aspectFill" aria-label="个人背景图，点击查看原图" @click="preview(url)"/><view v-else class="cover-placeholder"><LoopIcon name="exchange" :size="72" tone="primary"/><text>校园好物 · 继续有用</text></view></view></template>
<style scoped>.profile-cover{height:clamp(240px,28vw,420px);width:100%;overflow:hidden;border-radius:24px 24px 0 0;background:linear-gradient(120deg,var(--cl-primary-soft),var(--cl-blue-soft))}.profile-cover image{width:100%;height:100%;cursor:zoom-in}.cover-placeholder{height:100%;display:flex;align-items:center;justify-content:center;gap:24px;color:var(--cl-primary);font-size:20px;letter-spacing:3px}@media(max-width:600px){.profile-cover{height:230px;border-radius:20px 20px 0 0}.cover-placeholder{font-size:15px;gap:12px}}</style>
