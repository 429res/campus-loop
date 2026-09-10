<script setup>
import {reactive,ref,watch} from 'vue'
import http from '@/http'
import {useOverlayLock} from '@/composables/useOverlayLock'
const props=defineProps({user:Object,modelValue:Boolean});const emit=defineEmits(['update:modelValue','saved'])
const visible=ref(false),busy=ref(false),error=ref(''),uncertain=ref(false),form=reactive({role:'USER',permissions:[],reason:''})
const scopes={ALL:'全部权限（超级管理员）',USERS:'账号管理',ITEMS:'物品审核',CATEGORIES:'分类维护',EXCHANGES:'交换管理与争议',HISTORY:'履历核验',REPORTS:'举报处理',OPERATIONS:'运营统计与审计'}
watch(()=>props.modelValue,value=>{visible.value=value;if(value){form.role=props.user.role;form.permissions=[...(props.user.permissions||[])];form.reason='';error.value='';uncertain.value=false}})
useOverlayLock(visible)
function close(){if(!busy.value)emit('update:modelValue',false)}
async function save(){if(busy.value||uncertain.value)return;if(!form.reason.trim()){error.value='请填写调整原因';return}if(form.role==='ADMIN'&&!form.permissions.length){error.value='请至少选择一项权限';return}
 busy.value=true;error.value='';try{await http.patch(`/api/admin/users/${props.user.id}/access`,{version:props.user.version,role:form.role,permissions:form.role==='ADMIN'?form.permissions:[],reason:form.reason.trim()},{silent:true});emit('saved');emit('update:modelValue',false)}catch(e){error.value=e.response?.data?.msg||'未收到结果，请关闭窗口并刷新账号后查看';uncertain.value=!e.response||e.response.status===409}finally{busy.value=false}}
</script>
<template><el-dialog :model-value="modelValue" title="分配账号权限" width="min(560px,94vw)" :lock-scroll="false" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="close"><el-form label-position="top" @submit.prevent="save"><p>{{user?.displayName}} · @{{user?.username}}</p><el-form-item label="账号角色"><el-radio-group v-model="form.role" :disabled="busy||uncertain"><el-radio value="USER">普通用户</el-radio><el-radio value="ADMIN">管理员</el-radio></el-radio-group></el-form-item><el-form-item v-if="form.role==='ADMIN'" label="管理权限"><el-checkbox-group v-model="form.permissions" :disabled="busy||uncertain" class="permission-options"><el-checkbox v-for="(name,key) in scopes" :key="key" :value="key">{{name}}</el-checkbox></el-checkbox-group></el-form-item><el-form-item label="调整原因"><el-input v-model="form.reason" type="textarea" maxlength="500" show-word-limit :rows="3" :disabled="busy||uncertain"/></el-form-item><p class="hint">保存后，该账号需要重新登录。</p><el-alert v-if="error" :title="error" type="error" :closable="false"/><div class="actions"><el-button :disabled="busy" @click="close">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="uncertain">保存权限</el-button></div></el-form></el-dialog></template>
<style scoped>.permission-options{display:grid;grid-template-columns:1fr}.hint{font-size:13px;color:var(--cl-muted)}.actions{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}</style>
