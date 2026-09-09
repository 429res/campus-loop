<script setup>
import {ref,watch,computed} from 'vue'
import LoopButton from './LoopButton.vue'
import {publicMember} from '../common/member-profile.js'
import {imageUrl} from '../common/http'
const props=defineProps({id:Number,name:String,avatar:String,size:{type:Number,default:34},showName:{type:Boolean,default:true},showAvatar:{type:Boolean,default:true}})
const failed=ref(false),resolvedAvatar=ref('')
const avatarSource=computed(()=>props.avatar||resolvedAvatar.value)
watch(()=>[props.id,props.avatar],async()=>{failed.value=false;resolvedAvatar.value='';const id=props.id;if(id&&!props.avatar&&props.showAvatar){const profile=await publicMember(id);if(id===props.id)resolvedAvatar.value=profile?.avatarUrl||''}}, {immediate:true})
const open=()=>{if(props.id)uni.navigateTo({url:`/pages/member/member?id=${props.id}`})}
</script>
<template><LoopButton class="member-link" :aria-label="`查看 ${name||'同学'} 的个人主页`" @click.stop="open"><image v-if="showAvatar&&avatarSource&&!failed" :src="imageUrl(avatarSource)" :style="{width:size+'px',height:size+'px'}" mode="aspectFill" @error="failed=true"/><text v-else-if="showAvatar" class="cl-avatar" :style="{width:size+'px',height:size+'px'}">{{(name||'同学').slice(0,1)}}</text><text v-if="showName" class="member-name">{{name||'校园同学'}}</text></LoopButton></template>
<style scoped>.member-link{display:inline-flex;align-items:center;gap:8px;padding:0;background:transparent;color:var(--cl-text);text-align:left;font-size:inherit;min-width:0}.member-link image,.cl-avatar{border-radius:50%;flex:none}.member-name{white-space:nowrap;text-overflow:ellipsis;overflow:hidden;max-width:170px}</style>
