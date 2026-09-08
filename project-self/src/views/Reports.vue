<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { RefreshRight, Search } from "@element-plus/icons-vue";
import ReportActionDialog from "@/components/reports/ReportActionDialog.vue";
import { REPORT_ACTIONS } from "@/features/reports/reportForm";
import { useOverlayLock } from "@/composables/useOverlayLock";

const route = useRoute();
const fixture = import.meta.env.DEV && route.path === "/fixtures/reports";
const apiReady = false;
const filters = reactive({ keyword: "", status: "", targetType: "", page: 1, size: 8 });
const loading = ref(false), failed = ref(false), drawer = ref(false);
const actionVisible = ref(false), action = ref(REPORT_ACTIONS.ACCEPT), actionTarget = ref(null);
const outcome = ref("preview"), captured = ref(""), conflictRefreshes = ref(0), pendingFinish = ref(null);
const detail = ref(null);
useOverlayLock(drawer);

const fixtureReports = Object.freeze([
  { id: 2401, targetType:"ITEM", targetId:83, targetAvailable:true, targetSummary:"蓝牙键盘 · ITEM #83", reporterDisplayName:"匿名化用户 17", status:"SUBMITTED", version:0, reason:"物品描述与收到的实物情况不一致。\n请核对发布内容与证据。", evidence:[{id:301,displayName:"包装照片.png",contentType:"image/png",size:284103,accessStatus:"AVAILABLE"},{id:302,displayName:"补充说明.txt",contentType:"text/plain",size:430,accessStatus:"AVAILABLE",text:"照片拍摄于校内交接点，仅用于说明外包装状态。"}],createdAt:"2026-09-08T02:14:00Z",acceptedBy:null,acceptedAt:null,decision:null,decisionReason:null,decidedBy:null,decidedAt:null },
  { id:2399, targetType:"ITEM", targetId:71, targetAvailable:false, targetSummary:"目标已失效 · ITEM #71", reporterDisplayName:"匿名化用户 08", status:"IN_REVIEW", version:2, reason:"目标已不可访问，但举报记录和当时摘要应保留。", evidence:[{id:298,displayName:"证据已过期",contentType:null,size:null,accessStatus:"EXPIRED"}],createdAt:"2026-09-07T11:30:00Z",acceptedBy:{displayName:"陈管理员"},acceptedAt:"2026-09-07T12:02:00Z",decision:null,decisionReason:null,decidedBy:null,decidedAt:null },
  { id:2388, targetType:"ITEM", targetId:66, targetAvailable:true, targetSummary:"高等数学教材 · ITEM #66", reporterDisplayName:"匿名化用户 11", status:"RESOLVED", version:3, reason:"疑似重复发布。", evidence:[],createdAt:"2026-09-06T05:18:00Z",acceptedBy:{displayName:"林管理员"},acceptedAt:"2026-09-06T05:22:00Z",decision:"DISMISSED",decisionReason:"核对后为不同版本教材，举报不成立。",decidedBy:{displayName:"林管理员"},decidedAt:"2026-09-06T05:35:00Z" },
]);
const fixtureAudits = Object.freeze({
  2401: [{ id: 501, action: "SUBMIT", actor: "匿名化用户 17", previousStatus: null, newStatus: "SUBMITTED", previousVersion: null, newVersion: 0, reason: "提交举报", createdAt: "2026-09-08T02:14:00Z" }],
  2399: [{ id: 499, action: "ACCEPT", actor: "陈管理员", previousStatus: "SUBMITTED", newStatus: "IN_REVIEW", previousVersion: 1, newVersion: 2, reason: "开始核对目标与授权证据", createdAt: "2026-09-07T12:02:00Z" }],
  2388: [{ id: 488, action: "DECIDE", actor: "林管理员", previousStatus: "IN_REVIEW", newStatus: "RESOLVED", previousVersion: 2, newVersion: 3, reason: "核对后为不同版本教材，举报不成立。", createdAt: "2026-09-06T05:35:00Z" }],
});
const filtered = computed(() => {
  if (!fixture) return [];
  const keyword = filters.keyword.trim().toLowerCase();
  return fixtureReports.filter((item) => (!filters.status || item.status === filters.status) && (!filters.targetType || item.targetType === filters.targetType) && (!keyword || `${item.id} ${item.targetSummary}`.toLowerCase().includes(keyword)));
});
const records = computed(() => filtered.value.slice((filters.page-1)*filters.size, filters.page*filters.size));
const total = computed(() => filtered.value.length);

function search(){filters.page=1;}
function reset(){filters.keyword="";filters.status="";filters.targetType="";search();}
function show(row){detail.value=row;drawer.value=true;}
function openAction(row,nextAction){actionTarget.value=row;action.value=nextAction;captured.value="";actionVisible.value=true;}
function fixtureError(status){const error=new Error("fixture");error.status=status;return error;}
function submitFixture(payload){
  captured.value="";
  if(outcome.value==="conflict") return Promise.reject(fixtureError(409));
  if(outcome.value==="forbidden") return Promise.reject(fixtureError(403));
  if(outcome.value==="failure") return Promise.reject(fixtureError(503));
  if(outcome.value==="pending") return new Promise((resolve)=>{pendingFinish.value=()=>resolve({fixture:true,payload});});
  return Promise.resolve({fixture:true,payload});
}
function saved({payload}){captured.value=`已捕获请求体：${JSON.stringify(payload)}；未调用 API，未改变举报状态。`;actionVisible.value=false;pendingFinish.value=null;}
function finishPending(){pendingFinish.value?.();pendingFinish.value=null;}
async function refreshFixture(id){
  conflictRefreshes.value+=1;
  const current=fixtureReports.find((item)=>item.id===id);
  return {...current,version:current.version+1,status:current.status==="SUBMITTED"?"IN_REVIEW":current.status};
}
const statusLabel=(value)=>({SUBMITTED:"待受理",IN_REVIEW:"处理中",RESOLVED:"已处理"})[value]||value;
const statusType=(value)=>({SUBMITTED:"warning",IN_REVIEW:"primary",RESOLVED:"success"})[value]||"info";
const decisionLabel=(value)=>({UPHELD:"举报成立",DISMISSED:"举报不成立"})[value]||"尚未处理";
const dateTime=(value)=>value?.replace("T"," ").replace("Z"," UTC")||"—";
onMounted(()=>{ if(fixture) detail.value=fixtureReports[0]; });
</script>

<template>
  <div class="page-heading reports-heading"><div><span class="eyebrow">TRUST &amp; SAFETY</span><h1>举报处理队列</h1><p>受理通用举报并保留处理理由；交换争议的业务后果不在本页面执行。</p></div><span class="count-pill">{{ fixture ? `夹具共 ${total} 条` : "后端待接入" }}</span></div>
  <el-alert class="contract-alert" :title="fixture ? '开发组件夹具，不是正式举报队列' : '举报后端尚未实现'" :description="fixture ? '数据已匿名化且仅在开发路由中存在；提交只捕获请求体，不更新本地数组。' : 'A 尚未提供举报权限、持久化、证据授权和审计 API。正式页面不会请求不存在的接口，也不会显示伪成功。'" type="warning" :closable="false" show-icon />
  <section class="panel">
    <form class="filter-bar reports-filter" @submit.prevent="search">
      <el-input v-model="filters.keyword" aria-label="搜索举报" clearable placeholder="举报编号或目标摘要" :prefix-icon="Search" :disabled="!fixture" />
      <el-select v-model="filters.status" aria-label="举报状态" clearable placeholder="全部状态" :disabled="!fixture"><el-option v-for="state in ['SUBMITTED','IN_REVIEW','RESOLVED']" :key="state" :label="statusLabel(state)" :value="state" /></el-select>
      <el-select v-model="filters.targetType" aria-label="目标类型" clearable placeholder="全部目标" :disabled="!fixture"><el-option label="物品" value="ITEM" /></el-select>
      <el-button type="primary" native-type="submit" :loading="loading" :disabled="!fixture">搜索</el-button><el-button :icon="RefreshRight" :disabled="!fixture" @click="reset">重置</el-button>
    </form>
    <el-alert v-if="failed" title="举报读取失败，当前列表未被替换。" type="error" :closable="false" show-icon />
    <el-table :data="records" v-loading="loading" row-key="id" style="width:100%">
      <el-table-column label="举报与目标" min-width="270"><template #default="{row}"><strong>#{{ row.id }} · {{ row.targetSummary }}</strong><small class="table-sub">{{ row.targetAvailable ? '目标可访问' : '目标已失效（保留举报快照）' }}</small></template></el-table-column>
      <el-table-column prop="reporterDisplayName" label="提交人" min-width="130" />
      <el-table-column label="状态" min-width="120"><template #default="{row}"><el-tag :type="statusType(row.status)" round>{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="处理人" min-width="130"><template #default="{row}">{{ row.decidedBy?.displayName || row.acceptedBy?.displayName || '未分配' }}</template></el-table-column>
      <el-table-column label="提交时间" min-width="170"><template #default="{row}">{{ dateTime(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="215" fixed="right"><template #default="{row}"><el-button type="primary" link @click="show(row)">详情</el-button><el-button v-if="row.status==='SUBMITTED'" type="primary" link @click="openAction(row,REPORT_ACTIONS.ACCEPT)">受理</el-button><el-button v-if="row.status==='IN_REVIEW'" type="danger" link @click="openAction(row,REPORT_ACTIONS.DECIDE)">处理</el-button></template></el-table-column>
      <template #empty><el-empty :description="apiReady ? '没有符合条件的举报' : '举报 API 待 A 实现，正式队列未开放'" :image-size="72" /></template>
    </el-table>
    <div class="pagination-row"><span>长文本仅按文本渲染；证据不接受服务端路径或任意外链</span><el-pagination v-model:current-page="filters.page" :page-size="filters.size" :total="total" :pager-count="5" layout="prev, pager, next" background :disabled="!fixture" /></div>
  </section>
  <section v-if="fixture" class="panel fixture-panel"><div class="section-heading"><div><span class="eyebrow">LOCAL COMPONENT FIXTURE</span><h2>受理与处理错误恢复</h2><p>切换响应以检查防重入、权限失败和 409 回读。</p></div><el-tag type="warning" round>非正式入口</el-tag></div><div class="fixture-controls"><el-select v-model="outcome" aria-label="举报夹具响应场景"><el-option label="捕获请求体（不写入）" value="preview"/><el-option label="409 冲突并回读" value="conflict"/><el-option label="403 权限不足" value="forbidden"/><el-option label="请求失败" value="failure"/><el-option label="保持处理中（验证防重入）" value="pending"/></el-select><el-button :disabled="!records[0]" @click="openAction(fixtureReports[0],REPORT_ACTIONS.ACCEPT)">受理夹具</el-button><el-button type="danger" @click="openAction(fixtureReports[1],REPORT_ACTIONS.DECIDE)">处理夹具</el-button></div><el-alert v-if="captured" :title="captured" type="info" :closable="false"/><p v-if="conflictRefreshes" class="fixture-note">409 已回读 {{ conflictRefreshes }} 次；未覆盖服务端结果，也未自动重试提交。</p></section>
  <el-drawer v-model="drawer" :lock-scroll="false" title="举报、目标与证据详情" size="min(600px, 100vw)" destroy-on-close>
    <template v-if="detail"><div class="detail-tags"><el-tag>{{ detail.targetType }}</el-tag><el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag><el-tag :type="detail.targetAvailable?'success':'danger'">{{ detail.targetAvailable?'目标可访问':'目标已失效' }}</el-tag></div><h2>{{ detail.targetSummary }}</h2><dl class="detail-data"><div><dt>举报编号</dt><dd>#{{ detail.id }}</dd></div><div><dt>并发版本</dt><dd>{{ detail.version }}</dd></div><div><dt>提交人</dt><dd>{{ detail.reporterDisplayName }}</dd></div><div><dt>提交时间</dt><dd>{{ dateTime(detail.createdAt) }}</dd></div><div><dt>受理人</dt><dd>{{ detail.acceptedBy?.displayName||'未受理' }}</dd></div><div><dt>受理时间</dt><dd>{{ dateTime(detail.acceptedAt) }}</dd></div></dl><h3>举报理由</h3><p class="long-text">{{ detail.reason }}</p><h3>授权证据</h3><el-empty v-if="!detail.evidence.length" description="未提交证据" :image-size="56"/><div v-for="evidence in detail.evidence" :key="evidence.id" class="evidence-card"><strong>{{ evidence.displayName }}</strong><small>{{ evidence.contentType||'类型不可用' }} · {{ evidence.size ? `${evidence.size} bytes`:'大小不可用' }}</small><p v-if="evidence.text" class="long-text">{{ evidence.text }}</p><el-alert v-else-if="evidence.accessStatus!=='AVAILABLE'" title="证据已失效或当前账号不可访问" type="warning" :closable="false"/><el-button v-else disabled>受保护内容端点待 A 实现</el-button></div><h3>处理结果</h3><dl class="detail-data"><div><dt>决定</dt><dd>{{ decisionLabel(detail.decision) }}</dd></div><div><dt>处理人</dt><dd>{{ detail.decidedBy?.displayName||'—' }}</dd></div><div><dt>处理时间</dt><dd>{{ dateTime(detail.decidedAt) }}</dd></div><div class="detail-wide"><dt>处理理由</dt><dd class="long-text">{{ detail.decisionReason||'尚未处理' }}</dd></div></dl><h3>处理审计（组件夹具）</h3><div v-for="audit in fixtureAudits[detail.id] || []" :key="audit.id" class="audit-event"><strong>{{ audit.action }} · {{ audit.actor }}</strong><p>{{ audit.previousStatus || '新举报' }} → {{ audit.newStatus }} · v{{ audit.previousVersion ?? '—' }} → v{{ audit.newVersion }}</p><p class="long-text">{{ audit.reason }}</p><small>{{ dateTime(audit.createdAt) }}</small></div><el-alert v-if="!fixture" title="审计端点待 A 实现" type="info" :closable="false" /></template>
  </el-drawer>
  <ReportActionDialog :visible="actionVisible" :report="actionTarget" :action="action" :fixture="fixture" :submit-request="fixture?submitFixture:null" :refresh-request="fixture?refreshFixture:null" @close="actionVisible=false" @saved="saved" @conflict="()=>{}" @refreshed="actionTarget=$event"><template #default/><template #fixture-controls><el-button v-if="outcome==='pending'" :disabled="!pendingFinish" @click="finishPending">完成夹具请求</el-button></template></ReportActionDialog>
</template>

<style scoped>
.contract-alert{margin-bottom:20px}.reports-filter>.el-select{width:170px}.table-sub{display:block;margin-top:5px}.fixture-controls{display:flex;flex-wrap:wrap;gap:12px}.fixture-controls>.el-select{width:min(100%,270px)}.fixture-note{color:var(--cl-muted);font-size:12px}.long-text{white-space:pre-wrap;overflow-wrap:anywhere}.evidence-card,.audit-event{display:grid;gap:8px;padding:14px 0;border-bottom:1px solid var(--cl-border);overflow-wrap:anywhere}.evidence-card small,.audit-event small{color:var(--cl-muted)}.audit-event p{margin:0}.detail-wide{grid-column:1/-1}@media(max-width:640px){.reports-heading{align-items:flex-start;flex-direction:column}.fixture-controls>*{width:100%}}
</style>
