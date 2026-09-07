<script setup>
import LoopButton from './LoopButton.vue'
defineProps({modelValue:{type:Number,default:0},options:{type:Array,required:true}})
defineEmits(['update:modelValue'])
</script>
<template><view class="loop-segment cl-glass" role="group" aria-label="分段选择"><view class="segment-thumb" :style="{width:`calc((100% - 8px) / ${options.length})`,transform:`translateX(${modelValue * 100}%)`}"/><LoopButton v-for="(option,index) in options" :key="option" class="segment-option" :class="{selected:modelValue===index}" :aria-pressed="modelValue===index" @click="$emit('update:modelValue',index)">{{ option }}</LoopButton></view></template>
<style scoped>
.loop-segment{position:relative;display:flex;gap:0;padding:4px;border-radius:14px;width:fit-content;min-width:280px;isolation:isolate}.segment-thumb{position:absolute;left:4px;top:4px;bottom:4px;background:var(--cl-primary-soft);border:1px solid var(--cl-border);border-radius:10px;transition:transform var(--cl-motion-normal) var(--cl-ease);z-index:-1}.segment-option{flex:1;min-width:0;min-height:39px;padding:9px 16px;font-size:13px;color:var(--cl-muted);background:transparent}.segment-option.selected{color:var(--cl-primary);font-weight:650}@media(max-width:450px){.loop-segment{width:100%;min-width:0}.segment-option{padding:9px 12px}}
</style>
