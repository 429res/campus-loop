<script setup>
import {reactive,ref,watch} from 'vue'
import http from '@/http'
const props=defineProps({exchange:Object});const emit=defineEmits(['saved'])
const form=reactive({decision:'RESUME',reason:'',returnConfirmed:false}),busy=ref(false),error=ref(''),uncertain=ref(false)
watch(()=>props.exchange,()=>{form.decision='RESUME';form.reason='';form.returnConfirmed=false;error.value='';uncertain.value=false})
async function submit(){if(busy.value||uncertain.value)return;if(!form.reason.trim()){error.value='请填写处理原因';return}if(form.decision==='CANCEL'&&!form.returnConfirmed){error.value='请先确认物品归还情况';return}
 busy.value=true;error.value='';try{await http.post(`/api/admin/exchange-disputes/${props.exchange.id}/resolve`,{version:props.exchange.version,decision:form.decision,reason:form.reason.trim(),returnConfirmed:form.decision==='CANCEL'&&form.returnConfirmed},{silent:true});emit('saved')}catch(e){error.value=e.response?.data?.msg||'未收到处理结果，请刷新详情查看';uncertain.value=!e.response||e.response.status===409}finally{busy.value=false}}
</script>
<template><section class="resolution"><h3>处理争议</h3><el-form label-position="top" @submit.prevent="submit"><el-form-item label="处理方式"><el-radio-group v-model="form.decision" :disabled="busy||uncertain"><el-radio value="RESUME">恢复交接</el-radio><el-radio value="CANCEL">终止交换</el-radio></el-radio-group></el-form-item><p v-if="form.decision==='RESUME'">保留已有交接记录，由参与者继续确认。</p><el-checkbox v-else v-model="form.returnConfirmed" :disabled="busy||uncertain">已核实：物品仍由原持有人保管，或已全部归还</el-checkbox><el-form-item label="处理原因"><el-input v-model="form.reason" aria-label="争议处理原因" type="textarea" :rows="3" maxlength="1000" show-word-limit :disabled="busy||uncertain"/></el-form-item><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button type="primary" native-type="submit" :loading="busy" :disabled="uncertain">确认处理</el-button></el-form></section></template>
<style scoped>.resolution{padding:20px;border:1px solid var(--cl-border);border-radius:16px;margin:24px 0;background:var(--cl-surface)}p{font-size:13px;color:var(--cl-muted)}.el-button{margin-top:14px}.el-checkbox{height:auto;white-space:normal;margin-bottom:18px}</style>
