<script setup>
import LoopIcon from './LoopIcon.vue'
import LoopButton from './LoopButton.vue'
import { nextTick, onBeforeUnmount, ref, watch, computed } from 'vue'
import { useOverlayLock,pushDialog,popDialog,isTopDialog,dialogLayer } from '../composables/useOverlayLock'
import { isAppModalOpen } from '../common/modal'
const props = defineProps({modelValue:Boolean,dialog:Boolean,title:{type:String,default:'详情'}})
const emit = defineEmits(['update:modelValue'])
const panel = ref(null)
const close = () => emit('update:modelValue',false)
useOverlayLock(() => props.modelValue)
const dialogKey=Symbol()
const topDialog=computed(()=>isTopDialog(dialogKey))
const layer=computed(()=>dialogLayer(dialogKey))
watch(()=>props.modelValue,open=>open?pushDialog(dialogKey):popDialog(dialogKey),{immediate:true,flush:'sync'})
onBeforeUnmount(()=>popDialog(dialogKey))
// #ifdef H5
let previousFocus = null
let focusTimer = null
const keydown = event => {
  if (!props.modelValue || !isTopDialog(dialogKey) || isAppModalOpen()) return
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
      if(!props.modelValue || isAppModalOpen()) return
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
<template><view class="sheet-root" :class="{open:modelValue,dialog}" :aria-hidden="!modelValue || !topDialog" :inert="!modelValue || !topDialog" :style="{zIndex:layer}"><view class="sheet-mask" @click="close"/><view ref="panel" class="sheet-panel cl-glass" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1"><view class="sheet-header"><text class="cl-section-title">{{ title }}</text><LoopButton class="cl-icon-btn" :aria-label="dialog ? '关闭弹窗' : '关闭抽屉'" @click="close"><LoopIcon name="close"/></LoopButton></view><view class="sheet-body"><slot/></view></view></view></template>
<style scoped>
.sheet-root{position:fixed;inset:0;z-index:2000;visibility:hidden;pointer-events:none;transition:visibility var(--cl-motion-panel)}.sheet-mask{position:absolute;inset:0;background:var(--cl-overlay);opacity:0;transition:opacity var(--cl-motion-panel) ease}.sheet-panel{position:absolute;right:0;top:0;bottom:0;width:min(420px,92vw);border-radius:24px 0 0 24px;transform:translateX(100%);transition:transform var(--cl-motion-panel) var(--cl-ease);display:flex;flex-direction:column;overflow:hidden}.sheet-root.open{visibility:visible;pointer-events:auto}.open .sheet-mask{opacity:1}.open .sheet-panel{transform:translateX(0)}.sheet-header{display:flex;align-items:center;justify-content:space-between;padding:24px;gap:16px;border-bottom:1px solid var(--cl-border)}.sheet-body{padding:24px;overflow-y:auto;flex:1;background:var(--cl-surface)}
.sheet-header {flex:none;padding:20px;gap:12px}.sheet-header .cl-section-title {min-width:0;line-height:1.45;overflow-wrap:anywhere}.sheet-body {min-height:0;padding:20px 20px max(24px,env(safe-area-inset-bottom));overscroll-behavior:contain}.sheet-panel {max-height:100dvh}.sheet-header .cl-icon-btn {flex:none}
.dialog .sheet-panel{left:50%;right:auto;top:50%;bottom:auto;width:min(620px,94vw);max-height:88dvh;border-radius:24px;transform:translate(-50%,-46%) scale(.97)}.dialog.open .sheet-panel{transform:translate(-50%,-50%) scale(1)}.dialog .sheet-body{max-height:calc(88dvh - 85px)}
</style>
