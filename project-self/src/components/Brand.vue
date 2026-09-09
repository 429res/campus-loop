<script setup>
defineProps({ caption: Boolean });
</script>

<template>
  <span class="brand">
    <img class="brand-mark" src="/brand-mark.svg" alt="" aria-hidden="true" />
    <span>Campus <strong>Loop</strong><small v-if="caption">让闲置，继续有用</small></span>
  </span>
</template>
