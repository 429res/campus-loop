<script setup>
import { ref } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { useAuth } from '@/stores/auth'
import { canVisit,firstAllowed,routeScope } from '@/common/permissions'
import Brand from '@/components/Brand.vue'
const route=useRoute(),router=useRouter(),auth=useAuth(),busy=ref(false),error=ref('暂时无法连接平台，请检查网络后重试。')
async function retry(){if(busy.value)return;busy.value=true;try{await auth.refresh();const next=typeof route.query.redirect==='string'?route.query.redirect:'/';await router.replace(Object.hasOwn(routeScope,next)&&canVisit(auth.user,next)?next:firstAllowed(auth.user))}catch(e){if(!auth.token)await router.replace({path:'/login',query:{redirect:route.query.redirect||'/'}});else error.value='仍未连接成功，请稍后再试。'}finally{busy.value=false}}
</script>
<template><main class="connection"><Brand/><h1>连接暂时中断</h1><p role="alert">{{error}}</p><el-button type="primary" :loading="busy" @click="retry">重新连接</el-button></main></template>
<style scoped>.connection{max-width:520px;margin:15vh auto;padding:32px}.connection p{color:var(--cl-muted);line-height:1.8;margin-bottom:28px}</style>
