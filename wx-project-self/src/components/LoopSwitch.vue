<script setup>
import { ref } from 'vue'
import { useKeyboard } from '../composables/useKeyboard'
const control = ref(null)
defineProps({checked:Boolean,disabled:Boolean,color:{type:String,default:'#ca376f'}})
const keyboard = event => {
  // #ifdef H5
  if(event.key === 'Enter' || event.key === ' ') { event.preventDefault();event.currentTarget.click() }
  // #endif
}
useKeyboard(control,keyboard)
</script>
<template><switch ref="control" role="switch" :tabindex="disabled ? -1 : 0" :aria-checked="checked" :checked="checked" :disabled="disabled" :color="color" /></template>
