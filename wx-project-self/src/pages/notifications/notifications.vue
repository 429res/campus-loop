<script setup>
import LoopSkeleton from '../../components/LoopSkeleton.vue'
import {ref} from 'vue'
import {onShow,onUnload} from '@dcloudio/uni-app'
import LoopLayout from '../../components/LoopLayout.vue'
import LoopButton from '../../components/LoopButton.vue'
import http,{TOKEN_KEY} from '../../common/http'
const rows=ref([]),total=ref(0),page=ref(1),unreadOnly=ref(false),busy=ref(false),error=ref(''),writing=ref(false)
let generation=0
async function load(){const run=++generation,token=uni.getStorageSync(TOKEN_KEY);busy.value=true;error.value='';rows.value=[]
 try{const data=await http.get('/api/notifications',{page:page.value,size:15,unread:unreadOnly.value},{silent:true});if(run===generation&&token===uni.getStorageSync(TOKEN_KEY)){rows.value=data.records;total.value=data.total}}
 catch(e){if(run===generation)error.value=e.message}finally{if(run===generation)busy.value=false}}
function filter(){unreadOnly.value=!unreadOnly.value;page.value=1;load()}
async function read(row){if(writing.value)return;writing.value=true;const token=uni.getStorageSync(TOKEN_KEY)
 try{await http.patch(`/api/notifications/${row.id}/read`,{}, {silent:true});if(token!==uni.getStorageSync(TOKEN_KEY))return
 if(/^\/pages\/(exchanges|my-items|governance|history|community|profile-settings)\/[^\s]+$/.test(row.link))uni.navigateTo({url:row.link});else if(['/pages/profile/profile','/pages/matches/matches'].includes(row.link))uni.switchTab({url:row.link});else await load()
 }catch(e){error.value=e.message}finally{writing.value=false}}
async function readAll(){if(writing.value)return;writing.value=true;try{await http.post('/api/notifications/read-all',{}, {silent:true});page.value=1;await load()}catch(e){error.value=e.message}finally{writing.value=false}}
function turn(n){page.value+=n;load()}
const time=value=>value?new Date(/(?:Z|[+-]\d{2}:\d{2})$/.test(value)?value:value+'Z').toLocaleString('zh-CN',{hour12:false}):''
onShow(load);onUnload(()=>generation++)
</script>
<template><LoopLayout><view class="cl-page-heading"><text class="cl-title">消息通知</text></view><view class="cl-panel cl-stack">
<view class="cl-row"><LoopButton class="cl-btn" :disabled="busy||writing" @click="filter">{{unreadOnly?'仅看未读':'全部消息'}} · 切换</LoopButton><LoopButton class="cl-btn" :disabled="busy||writing" @click="readAll">全部标为已读</LoopButton></view>
<view v-if="error" class="cl-empty"><text class="cl-error" role="alert">{{error}}</text><LoopButton class="cl-btn" @click="load">重试</LoopButton></view><LoopSkeleton v-if="busy"/>
<view v-for="row in rows" :key="row.id" class="message"><view class="cl-row"><text class="cl-field-title">{{row.title}}</text><text v-if="!row.readAt" class="cl-tag">未读</text></view><text class="body">{{row.body}}</text><view class="cl-row"><text class="cl-hint">{{time(row.createdAt)}}</text><LoopButton class="cl-btn" :disabled="writing" @click="read(row)">查看</LoopButton></view></view>
<view v-if="!busy&&!error&&!rows.length" class="cl-empty">{{unreadOnly?'暂时没有未读消息':'还没有消息'}}</view><view class="cl-row"><LoopButton class="cl-btn" :disabled="busy||page<=1" @click="turn(-1)">上一页</LoopButton><text>{{page}} · 共 {{total}} 条</text><LoopButton class="cl-btn" :disabled="busy||page*15>=total" @click="turn(1)">下一页</LoopButton></view>
</view></LoopLayout></template>
<style scoped>.message{display:flex;flex-direction:column;gap:12px;padding:22px 0;border-bottom:1px solid var(--cl-border)}.body{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8}</style>
