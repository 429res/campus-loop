<script setup>
import LoopButton from './LoopButton.vue'
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useOverlayLock } from '../composables/useOverlayLock'
const props = defineProps({modelValue:Boolean,title:{type:String,default:'详情'}})
const emit = defineEmits(['update:modelValue'])
const panel = ref(null)
const close = () => emit('update:modelValue',false)
useOverlayLock(() => props.modelValue)
// #ifdef H5
let previousFocus = null
let focusTimer = null
const keydown = event => {
  if (!props.modelValue) return
  if (event.key === 'Escape') { event.preventDefault();close();return }
  if (event.key !== 'Tab') return
  const element = panel.value?.$el || panel.value
  const targets = element?.querySelectorAll('a[href],button:not([disabled]),[role="button"]:not([aria-disabled="true"]),input:not([disabled]),textarea:not([disabled]),select:not([disabled]),[role="switch"],[role="slider"],[tabindex]:not([tabindex="-1"])')
  if (!targets?.length) return
  const first = targets[0], last = targets[targets.length-1]
  if (!element.contains(document.activeElement)) {
    event.preventDefault()
    ;(event.shiftKey ? last : first).focus()
    return
  }
  if (event.shiftKey && document.activeElement === first) {event.preventDefault();last.focus()}
  if (!event.shiftKey && document.activeElement === last) {event.preventDefault();first.focus()}
}
watch(() => props.modelValue, async open => {
  if (open) {
    clearTimeout(focusTimer)
    previousFocus = document.activeElement
    document.addEventListener('keydown',keydown)
    await nextTick()
    const element = panel.value?.$el || panel.value
    focusTimer = setTimeout(() => {
      if(!props.modelValue) return
      ;(element?.querySelector('input:not([disabled]),textarea:not([disabled]),select:not([disabled])') || element)?.focus?.()
    }, 0)
  } else {
    clearTimeout(focusTimer)
    document.removeEventListener('keydown',keydown)
    previousFocus?.focus?.()
  }
})
onBeforeUnmount(() => { clearTimeout(focusTimer);document.removeEventListener('keydown',keydown) })
// #endif
</script>
<template><view class="sheet-root" :class="{open:modelValue}" :aria-hidden="!modelValue" :inert="!modelValue"><view class="sheet-mask" @click="close"/><view ref="panel" class="sheet-panel cl-glass" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1"><view class="sheet-header"><text class="cl-section-title">{{ title }}</text><LoopButton class="cl-icon-btn" aria-label="关闭抽屉" @click="close">×</LoopButton></view><view class="sheet-body"><slot/></view></view></view></template>
<style scoped>
.sheet-root{position:fixed;inset:0;z-index:2000;visibility:hidden;pointer-events:none;transition:visibility var(--cl-motion-panel)}.sheet-mask{position:absolute;inset:0;background:var(--cl-overlay);opacity:0;transition:opacity var(--cl-motion-panel) ease}.sheet-panel{position:absolute;right:0;top:0;bottom:0;width:min(420px,92vw);border-radius:24px 0 0 24px;transform:translateX(100%);transition:transform var(--cl-motion-panel) var(--cl-ease);display:flex;flex-direction:column;overflow:hidden}.sheet-root.open{visibility:visible;pointer-events:auto}.open .sheet-mask{opacity:1}.open .sheet-panel{transform:translateX(0)}.sheet-header{display:flex;align-items:center;justify-content:space-between;padding:24px;gap:16px;border-bottom:1px solid var(--cl-border)}.sheet-body{padding:24px;overflow-y:auto;flex:1;background:var(--cl-surface)}
</style>
