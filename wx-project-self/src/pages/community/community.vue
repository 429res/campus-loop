<script setup>
import {ref} from 'vue'
import {onLoad} from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import CommunityFeed from '../../components/CommunityFeed.vue'
const id=ref(0)
onLoad(options=>{const value=Number(options.id);if(Number.isSafeInteger(value)&&value>0)id.value=value})
</script>
<template><LoopLayout><view class="cl-page-heading"><text class="cl-title">校园讨论</text></view><CommunityFeed v-if="id" :post-id="id"/><view v-else class="cl-panel cl-empty">没有找到这条动态</view></LoopLayout></template>
