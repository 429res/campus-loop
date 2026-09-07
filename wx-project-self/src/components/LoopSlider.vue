<script setup>
import { ref } from 'vue'
import { useKeyboard } from '../composables/useKeyboard'
const control = ref(null)
const props = defineProps({modelValue:{type:Number,default:40}})
const emit = defineEmits(['update:modelValue'])
const change = event => emit('update:modelValue',Number(event.detail.value))
const keyboard = event => {
  // #ifdef H5
  if(['ArrowLeft','ArrowDown','ArrowRight','ArrowUp','Home','End'].includes(event.key)) {
    event.preventDefault()
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? 100 : props.modelValue + (['ArrowLeft','ArrowDown'].includes(event.key) ? -1 : 1)
    emit('update:modelValue',Math.max(0,Math.min(100,next)))
  }
  // #endif
}
useKeyboard(control,keyboard)
</script>
<template><slider ref="control" :value="modelValue" role="slider" tabindex="0" :aria-valuenow="modelValue" aria-valuemin="0" aria-valuemax="100" activeColor="#d63f78" show-value @change="change"/></template>
