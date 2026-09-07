<template>
  <span class="brand"
    ><svg viewBox="0 0 40 40" fill="none" aria-hidden="true">
      <rect width="40" height="40" rx="13" fill="currentColor" />
      <path
        d="M12 15a8 8 0 0 1 14-2l3 3m0 0v-7m0 7h-7M28 25a8 8 0 0 1-14 2l-3-3m0 0v7m0-7h7"
        stroke="white"
        stroke-width="2.5"
        stroke-linecap="round"
        stroke-linejoin="round"
      /></svg
    ><span
      >Campus <strong>Loop</strong
      ><small v-if="caption">让闲置，继续有用</small></span
    ></span
  >
</template>
<script setup>
defineProps({ caption: Boolean });
</script>
