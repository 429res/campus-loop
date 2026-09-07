<script setup>
import { ref } from 'vue'
import { useKeyboard } from '../composables/useKeyboard'
const control = ref(null)
// UniApp's H5 <uni-button> is a custom element, so provide keyboard semantics explicitly.
defineProps({disabled:Boolean})
const keyboard = event => {
  // #ifdef H5
  if ((event.key === 'Enter' || event.key === ' ') && !event.currentTarget.hasAttribute('disabled')) {
    event.preventDefault()
    event.currentTarget.click()
  }
  // #endif
}
useKeyboard(control,keyboard)
</script>
<template><button ref="control" role="button" :tabindex="disabled ? -1 : 0" :aria-disabled="disabled" :disabled="disabled"><slot/></button></template>
