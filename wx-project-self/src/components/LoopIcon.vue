<script setup>
import { computed } from 'vue'
defineOptions({
  // #ifdef MP-WEIXIN
  options: { virtualHost: true, styleIsolation: 'shared' },
  // #endif
})
const props = defineProps({name:{type:String,default:'loop'},tone:{type:String,default:'default'},size:{type:Number,default:22}})
const names = ['home', 'exchange', 'plus', 'user', 'search', 'close', 'sun', 'moon', 'heart', 'arrow', 'grid', 'target', 'alert', 'loop', 'box', 'check']
const position = computed(() => Math.max(0,names.indexOf(props.name)))
</script>
<template><view class="loop-icon" :class="`loop-icon--${tone}`" :style="{fontSize:`${size}px`}" aria-hidden="true"><view class="loop-icon-clip"><image class="loop-icon-atlas" src="/static/ui-icons.png" mode="scaleToFill" :style="{left:`-${position}em`}" /></view></view></template>
<style>
/* The 3x atlas is exported from shared/ui-icons.svg; no icon font metrics. */
.loop-icon{position:relative;overflow:hidden;display:inline-block;vertical-align:middle;width:1em;height:1em;min-width:1em;flex-shrink:0;pointer-events:none;--cl-icon-row:0em}
.loop-icon .loop-icon-clip{display:block;position:relative;overflow:hidden;width:1em;height:1em}
.loop-icon .loop-icon-atlas{position:absolute;top:var(--cl-icon-row);width:16em;height:5em;max-width:none}
.theme-dark .loop-icon{--cl-icon-row:-1em}
.loop-icon.loop-icon--primary{--cl-icon-row:-2em}
.theme-dark .loop-icon.loop-icon--primary{--cl-icon-row:-4em}
.loop-icon.loop-icon--white{--cl-icon-row:-3em}
</style>
