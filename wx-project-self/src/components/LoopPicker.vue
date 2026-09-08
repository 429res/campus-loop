<script setup>
defineProps({range:{type:Array,default:()=>[]},rangeKey:{type:String,default:''},value:{type:[Number,String],default:-1},mode:{type:String,default:'selector'},disabled:Boolean,ariaLabel:{type:String,default:''}})
const emit = defineEmits(['change'])
const change = event => emit('change',{detail:{value:event.target.value}})
</script>
<template>
  <!-- #ifdef H5 -->
  <component v-if="mode==='date'" :is="'input'" class="native-picker" type="date" :value="value" :disabled="disabled" :aria-label="ariaLabel" @change="change"/>
  <component v-else :is="'select'" class="native-picker" :value="value" :disabled="disabled" :aria-label="ariaLabel" @change="change"><option v-if="Number(value)<0" value="-1" disabled>请选择分类</option><option v-for="(option,index) in range" :key="index" :value="index">{{ rangeKey ? option[rangeKey] : option }}</option></component>
  <!-- #endif -->
  <!-- #ifndef H5 -->
  <picker :range="range" :range-key="rangeKey" :value="mode==='date' ? value : Math.max(0,Number(value))" :mode="mode" :disabled="disabled" :aria-label="ariaLabel" @change="emit('change',$event)"><slot/></picker>
  <!-- #endif -->
</template>
<style scoped>
.native-picker{box-sizing:border-box;width:100%;min-width:0;min-height:46px;padding:12px 14px;border:1px solid var(--cl-border);border-radius:12px;color:var(--cl-text);background:var(--cl-surface-soft);font:inherit;font-size:14px;color-scheme:inherit}.native-picker:focus-visible{outline:3px solid var(--cl-blue);outline-offset:3px}.native-picker[disabled]{opacity:.48}
</style>
