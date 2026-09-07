<script setup>
import LoopButton from './LoopButton.vue'
import { imageUrl } from '../common/http'
defineProps({ item: { type:Object, required:true } })
const open = id => uni.navigateTo({url:`/pages/detail/detail?id=${id}`})
</script>
<template>
  <LoopButton class="cl-card item-card" :aria-label="`查看 ${item.title}`" @click="open(item.id)">
    <view class="item-image-wrap"><image class="item-image" :src="imageUrl(item.imageUrl,item.title)" mode="aspectFill" /><text class="item-condition">{{ ['','有使用痕迹','正常使用','成色良好','几乎全新','全新未用'][item.conditionLevel] || '成色待补充' }}</text></view>
    <view class="item-content"><text class="item-title">{{ item.title }}</text><text class="item-want">想换 <text class="item-want-value">{{ item.wantedCategoryName || '合适的校园好物' }}</text></text><view class="item-bottom"><view class="item-owner"><text class="cl-avatar">{{ (item.ownerName || '同学').slice(0,1) }}</text><text>{{ item.ownerName || '校园同学' }}</text></view><text class="item-state">{{ item.status === 'AVAILABLE' ? '可交换' : '暂不可交换' }}</text></view></view>
  </LoopButton>
</template>
<style scoped>
.item-card{padding:0;border-radius:16px;background:var(--cl-surface);border:1px solid var(--cl-border);overflow:hidden;text-align:left;width:100%;color:var(--cl-text)}
.item-image-wrap{position:relative;width:100%;aspect-ratio:1.28;background:var(--cl-surface-soft);overflow:hidden}.item-image{width:100%;height:100%;position:absolute}.item-condition{position:absolute;left:12px;bottom:12px;font-size:10px;padding:5px 8px;background:rgba(255,255,255,.94);color:#394357;border-radius:6px}.item-content{padding:15px}.item-title{display:block;font-size:15px;font-weight:650;line-height:1.5;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.item-want{display:block;margin-top:10px;font-size:12px;color:var(--cl-muted)}.item-want-value{color:var(--cl-primary);font-weight:550;margin-left:5px}.item-bottom{display:flex;justify-content:space-between;align-items:center;gap:5px;margin-top:20px}.item-owner{display:flex;align-items:center;gap:7px;font-size:11px;color:var(--cl-muted);min-width:0}.item-owner>text:last-child{overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.item-state{font-size:10px;color:var(--cl-success);flex-shrink:0}.cl-avatar{width:24px;height:24px;font-size:10px}
@media(max-width:500px){.item-content{padding:11px}.item-title{font-size:13px}.item-bottom{margin-top:15px}.item-state{display:none}.item-condition{left:8px;bottom:8px;padding:4px 6px}.item-want{font-size:11px}}
</style>
