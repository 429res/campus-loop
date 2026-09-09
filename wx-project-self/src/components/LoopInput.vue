<script setup>
import { computed, ref } from 'vue'
defineOptions({
  // #ifdef MP-WEIXIN
  options: { virtualHost: true, styleIsolation: 'shared' },
  // #endif
})
const props = defineProps({ modelValue: { type: [String, Number], default: '' }, password: Boolean, multiline: Boolean, type: { type: String, default: 'text' }, disabled: Boolean, placeholder: String, maxlength: { type: [String, Number], default: 140 }, ariaLabel: String, ariaInvalid: [Boolean, String], autocomplete: String, confirmType: { type: String, default: 'done' } })
const emit = defineEmits(['update:modelValue', 'confirm'])
const value = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const composing = ref(false)
function input(event) { if (!composing.value) emit('update:modelValue', event.target.value) }
function compositionEnd(event) { composing.value = false; input(event) }
function confirm(event) {
  if (!props.multiline && !event.isComposing && !composing.value) emit('confirm', { detail: { value: event.target.value } })
}
</script>
<template>
  <!-- H5 uses the native element so labels, autocomplete and focus reach the actual input. -->
  <!-- #ifdef H5 -->
  <component :is="multiline ? 'textarea' : 'input'" class="loop-native-input" :disabled="disabled" :placeholder="placeholder" :maxlength="maxlength" :aria-label="ariaLabel" :aria-invalid="ariaInvalid" :autocomplete="autocomplete" :confirm-type="confirmType" :value="modelValue" :type="multiline ? undefined : password ? 'password' : type" @input="input" @compositionstart="composing = true" @compositionend="compositionEnd" @keydown.enter="confirm" />
  <!-- #endif -->
  <!-- #ifndef H5 -->
  <textarea v-if="multiline" class="cl-textarea" :disabled="disabled" :placeholder="placeholder" :maxlength="maxlength" :aria-label="ariaLabel" :aria-invalid="ariaInvalid" :autocomplete="autocomplete" :confirm-type="confirmType" v-model="value" @confirm="emit('confirm', $event)" />
  <input v-else class="cl-input" :disabled="disabled" :placeholder="placeholder" :maxlength="maxlength" :aria-label="ariaLabel" :aria-invalid="ariaInvalid" :autocomplete="autocomplete" :confirm-type="confirmType" v-model="value" :password="password" :type="type" @confirm="emit('confirm', $event)" />
  <!-- #endif -->
</template>
<style scoped>
.loop-native-input { box-sizing: border-box; display: block; min-width: 0; color: var(--cl-text); font: inherit; }
</style>
