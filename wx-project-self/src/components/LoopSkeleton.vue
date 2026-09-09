<script setup>
defineProps({count:{type:Number,default:3}})
</script>
<template><view class="loop-skeleton" role="status" aria-label="内容加载中" aria-busy="true"><view v-for="n in count" :key="n" class="skeleton-card"><view class="skeleton-thumb"/><view class="skeleton-lines"><view/><view/><view/></view></view></view></template>
<style scoped>
.loop-skeleton{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(260px,100%),1fr));gap:20px;padding:18px 0}.skeleton-card{border:1px solid var(--cl-border);border-radius:20px;padding:22px;background:var(--cl-surface);min-height:170px}.skeleton-thumb,.skeleton-lines>view{background:var(--cl-surface-soft);border-radius:10px;animation:skeleton-pulse 1.4s ease-in-out infinite alternate}.skeleton-thumb{height:72px;margin-bottom:22px}.skeleton-lines>view{height:12px;margin-top:12px;width:85%}.skeleton-lines>view:last-child{width:55%}@keyframes skeleton-pulse{to{opacity:.35}}
</style>
