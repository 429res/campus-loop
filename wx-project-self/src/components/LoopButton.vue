<script setup>
import { computed, ref } from 'vue'
import { useKeyboard } from '../composables/useKeyboard'

defineOptions({
  // A native form must be able to find the button across this component boundary.
  // #ifdef MP-WEIXIN
  behaviors: ['wx://form-field-button'],
  // #endif
})

const props = defineProps({
  disabled: Boolean,
  loading: Boolean,
  formType: { type: String, default: '' },
  ariaLabel: String,
  ariaPressed: { type: [Boolean, String], default: undefined },
  ariaCurrent: { type: [Boolean, String], default: undefined },
})
// Declaring click also removes its H5 fallthrough listener, avoiding double calls.
const emit = defineEmits(['click'])
const control = ref(null)
const blocked = computed(() => props.disabled || props.loading)
const click = event => {
  if (!blocked.value) emit('click', event)
}

// UniApp's H5 <uni-button> is a custom element, so provide keyboard semantics explicitly.
const keyboard = event => {
  // #ifdef H5
  if (event.key !== 'Enter' && event.key !== ' ') return
  event.preventDefault()
  if (blocked.value || event.repeat || event.isComposing) return
  event.currentTarget.click()
  // #endif
}
useKeyboard(control, keyboard)
</script>
<template>
  <button
    ref="control"
    role="button"
    :tabindex="blocked ? -1 : 0"
    :aria-label="ariaLabel"
    :aria-pressed="ariaPressed"
    :aria-current="ariaCurrent"
    :aria-disabled="blocked"
    :aria-busy="loading"
    :disabled="blocked"
    :loading="loading"
    :form-type="formType"
    @click="click"
  ><slot/></button>
</template>
