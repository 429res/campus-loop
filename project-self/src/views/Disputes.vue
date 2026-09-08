<script setup>
import { computed, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { RefreshRight, Search } from "@element-plus/icons-vue";
import ExchangeTrace from "@/components/exchanges/ExchangeTrace.vue";
import { disputeImpactFacts } from "@/features/disputes/disputePresentation";
import { useOverlayLock } from "@/composables/useOverlayLock";

const route = useRoute();
const fixture = import.meta.env.DEV && route.path === "/fixtures/disputes";
const filters = reactive({ keyword: "", participant: "", page: 1, size: 8 });
const scenario = ref("records"), loading = ref(false), failed = ref(false), drawer = ref(false), detail = ref(null);
useOverlayLock(drawer);

const fixtures = Object.freeze([
  {
    id: 4102, status: "DISPUTED", version: 6, createdAt: "2026-09-07T08:00:00Z", expiresAt: "2026-09-08T08:00:00Z",
    disputedBy: 102, disputedByName: "虚构同学乙", disputeReason: "收到的键盘外壳有明显裂纹，与交接前说明不一致。\n我已停止后续交接，请管理员核对双方说明。", disputedAt: "2026-09-07T09:18:00Z", allowedActions: [],
    participants: [
      { userId:101,displayName:"虚构同学甲",offeredItemId:201,receivedItemId:202,confirmationStatus:"CONFIRMED",confirmedAt:"2026-09-07T08:04:00Z",handedOffAt:"2026-09-07T09:00:00Z",receivedAt:null,handedOffNote:"在东区交接点交出，外观已当面展示。",receivedNote:null },
      { userId:102,displayName:"虚构同学乙",offeredItemId:202,receivedItemId:201,confirmationStatus:"CONFIRMED",confirmedAt:"2026-09-07T08:06:00Z",handedOffAt:null,receivedAt:"2026-09-07T09:12:00Z",handedOffNote:null,receivedNote:"收到后发现外壳裂纹。" },
    ],
    flows:[{itemId:201,fromUserId:101,toUserId:102},{itemId:202,fromUserId:102,toUserId:101}],
    events:[
      {id:901,type:"CONFIRMED",actor:"虚构同学甲",from:"AWAITING_CONFIRMATION",to:"AWAITING_CONFIRMATION",fromVersion:0,toVersion:1,reason:null,at:"2026-09-07T08:04:00Z"},
      {id:902,type:"CONFIRMED",actor:"虚构同学乙",from:"AWAITING_CONFIRMATION",to:"READY",fromVersion:1,toVersion:2,reason:null,at:"2026-09-07T08:06:00Z"},
      {id:903,type:"HANDED_OFF",actor:"虚构同学甲",from:"READY",to:"READY",fromVersion:2,toVersion:3,reason:"在东区交接点交出，外观已当面展示。",at:"2026-09-07T09:00:00Z"},
      {id:904,type:"RECEIVED",actor:"虚构同学乙",from:"READY",to:"READY",fromVersion:3,toVersion:4,reason:"收到后发现外壳裂纹。",at:"2026-09-07T09:12:00Z"},
      {id:905,type:"DISPUTED",actor:"虚构同学乙",from:"READY",to:"DISPUTED",fromVersion:5,toVersion:6,reason:"收到的键盘外壳有明显裂纹，与交接前说明不一致。",at:"2026-09-07T09:18:00Z"},
    ],
  },
  {
    id: 4097, status: "DISPUTED", version: 8, createdAt: "2026-09-06T01:30:00Z", expiresAt: "2026-09-07T01:30:00Z",
    disputedBy: 203, disputedByName: "虚构同学丙", disputeReason: "三方交接中有一件物品未按约定到场。" + "补充说明较长，仅作为纯文本展示，不执行链接或标记。".repeat(8), disputedAt: "2026-09-06T04:10:00Z", allowedActions: [],
    participants:[
      {userId:201,displayName:"虚构同学甲",offeredItemId:301,receivedItemId:303,confirmationStatus:"CONFIRMED",handedOffAt:"2026-09-06T03:00:00Z",receivedAt:null,handedOffNote:"已交出",receivedNote:null},
      {userId:202,displayName:"虚构同学乙",offeredItemId:302,receivedItemId:301,confirmationStatus:"CONFIRMED",handedOffAt:"2026-09-06T03:15:00Z",receivedAt:"2026-09-06T03:16:00Z",handedOffNote:"已交出",receivedNote:"已收到"},
      {userId:203,displayName:"虚构同学丙",offeredItemId:303,receivedItemId:302,confirmationStatus:"CONFIRMED",handedOffAt:null,receivedAt:null,handedOffNote:null,receivedNote:null},
    ],
    flows:[{itemId:301,fromUserId:201,toUserId:202},{itemId:302,fromUserId:202,toUserId:203},{itemId:303,fromUserId:203,toUserId:201}],
    events:[{id:890,type:"DISPUTED",actor:"虚构同学丙",from:"READY",to:"DISPUTED",fromVersion:7,toVersion:8,reason:"三方交接中有一件物品未按约定到场。",at:"2026-09-06T04:10:00Z"}],
  },
]);

const matched = computed(() => {
  if (!fixture || scenario.value === "empty" || scenario.value === "error") return [];
  const keyword = filters.keyword.trim().toLowerCase();
  const participant = filters.participant.trim().toLowerCase();
  return fixtures.filter((exchange) => (!keyword || `${exchange.id} ${exchange.disputeReason}`.toLowerCase().includes(keyword)) && (!participant || exchange.participants.some((person) => person.displayName.toLowerCase().includes(participant))));
});
const records = computed(() => matched.value.slice((filters.page-1)*filters.size,filters.page*filters.size));
const total = computed(() => matched.value.length);
const time=(value)=>value?.replace("T"," ").replace("Z"," UTC")||"—";
function search(){filters.page=1;failed.value=scenario.value==="error";}
function reset(){filters.keyword="";filters.participant="";failed.value=false;scenario.value="records";search();}
function show(row){detail.value=row;drawer.value=true;}
function applyScenario(){loading.value=scenario.value==="loading";failed.value=scenario.value==="error";}
</script>

<template>
  <div class="page-heading dispute-heading"><div><span class="eyebrow">EXCHANGE TRACE</span><h1>交换争议追溯</h1><p>查看交接事实与持久流向；裁决必须由 B 的同一交换领域事务实现。</p></div><span class="count-pill">{{ fixture ? `夹具 ${total} 条` : "管理读取待开发" }}</span></div>
  <el-alert class="contract-alert" :title="fixture?'开发组件夹具，不是正式争议队列':'管理员争议读取与裁决尚未实现'" :description="fixture?'固定虚构数据只验证只读追溯和错误状态，不调用 API、不改变交换。':'现有接口只允许参与者登记争议，普通管理员不能读取参与者详情；本页不会调用参与者接口冒充管理权限。'" type="warning" :closable="false" show-icon />
  <section class="impact-panel panel"><div><span class="eyebrow">CURRENT DOMAIN EFFECT</span><h2>DISPUTED 的已实现影响</h2></div><ul><li v-for="fact in disputeImpactFacts({status:'DISPUTED'})" :key="fact">{{ fact }}</li></ul><el-button disabled>裁决接口待 B/A 实现</el-button></section>
  <section class="panel">
    <form class="filter-bar dispute-filter" @submit.prevent="search"><el-input v-model="filters.keyword" aria-label="搜索争议" clearable placeholder="交换编号或争议理由" :prefix-icon="Search" :disabled="!fixture"/><el-input v-model="filters.participant" aria-label="搜索参与者" clearable placeholder="参与者显示名" :disabled="!fixture"/><el-button type="primary" native-type="submit" :disabled="!fixture">搜索</el-button><el-button :icon="RefreshRight" :disabled="!fixture" @click="reset">重置</el-button></form>
    <el-alert v-if="failed" title="争议队列读取失败，旧内容不会被错误响应替换。" type="error" :closable="false" show-icon><template #default><el-button @click="reset">恢复夹具数据</el-button></template></el-alert>
    <el-table :data="records" v-loading="loading" row-key="id" style="width:100%"><el-table-column label="交换" min-width="150"><template #default="{row}"><strong>#{{row.id}}</strong><small class="table-sub">版本 {{row.version}}</small></template></el-table-column><el-table-column label="争议登记人" min-width="140"><template #default="{row}">{{row.disputedByName}}</template></el-table-column><el-table-column label="当前状态" min-width="120"><template #default><el-tag type="danger" round>争议中</el-tag></template></el-table-column><el-table-column label="参与与流向" min-width="150"><template #default="{row}">{{row.participants.length}} 人 · {{row.flows.length}} 件物品</template></el-table-column><el-table-column label="登记时间" min-width="180"><template #default="{row}">{{time(row.disputedAt)}}</template></el-table-column><el-table-column label="操作" width="150" fixed="right"><template #default="{row}"><el-button type="primary" link @click="show(row)">查看追溯</el-button></template></el-table-column><template #empty><el-empty :description="failed?'读取失败，请重试':fixture?'没有符合条件的争议':'ADMIN 交换读取接口待开发'" :image-size="72"/></template></el-table>
    <div class="pagination-row"><span>管理读取不能代替参与者确认、交接或争议登记</span><el-pagination v-model:current-page="filters.page" :page-size="filters.size" :total="total" layout="prev, pager, next" background :disabled="!fixture"/></div>
  </section>
  <section v-if="fixture" class="panel fixture-panel"><div class="section-heading"><div><span class="eyebrow">LOCAL COMPONENT FIXTURE</span><h2>队列状态夹具</h2><p>加载、错误和空态只影响本地展示，不产生交换写入。</p></div><el-tag type="warning" round>非正式入口</el-tag></div><div class="fixture-controls"><el-select v-model="scenario" aria-label="争议队列夹具场景" @change="applyScenario"><el-option label="固定追溯数据" value="records"/><el-option label="加载中" value="loading"/><el-option label="读取失败" value="error"/><el-option label="空队列" value="empty"/></el-select><el-button @click="reset">恢复夹具</el-button></div></section>
  <el-drawer v-model="drawer" :lock-scroll="false" title="交换争议事实" size="min(720px, 100vw)" destroy-on-close>
    <template v-if="detail"><div class="detail-tags"><el-tag type="danger">DISPUTED</el-tag><el-tag>交换 #{{detail.id}}</el-tag><el-tag>版本 {{detail.version}}</el-tag></div><h2>争议登记</h2><dl class="detail-data"><div><dt>登记人</dt><dd>{{detail.disputedByName}}</dd></div><div><dt>登记时间</dt><dd>{{time(detail.disputedAt)}}</dd></div><div class="detail-wide"><dt>争议理由</dt><dd class="long-text">{{detail.disputeReason}}</dd></div></dl><ExchangeTrace :exchange="detail"/><h3>交换事件</h3><div v-for="event in detail.events" :key="event.id" class="event-card"><strong>{{event.type}} · {{event.actor}}</strong><p>{{event.from}} → {{event.to}} · v{{event.fromVersion}} → v{{event.toVersion}}</p><p class="long-text">{{event.reason||'无附加说明'}}</p><small>{{time(event.at)}}</small></div><h3>证据与裁决</h3><el-alert title="B-04 当前凭据仅为参与者交出/收到声明、说明和时间；没有争议附件契约。" type="info" :closable="false"/><ul class="impact-list"><li v-for="fact in disputeImpactFacts(detail)" :key="fact">{{fact}}</li></ul><el-button type="danger" disabled>管理员裁决待 B/A 定义</el-button><p class="pending-note">未定义决定枚举、版本/幂等请求、证据授权与原子后果前，不提供提交入口。</p></template>
  </el-drawer>
</template>

<style scoped>
.contract-alert{margin-bottom:20px}.impact-panel{display:grid;grid-template-columns:minmax(180px,.8fr) minmax(280px,1.6fr) auto;align-items:center;gap:24px}.impact-panel h2{margin:4px 0}.impact-panel ul,.impact-list{margin:0;padding-left:20px}.dispute-filter>input,.dispute-filter>.el-input{max-width:260px}.table-sub{display:block;margin-top:4px}.fixture-controls{display:flex;gap:12px;flex-wrap:wrap}.fixture-controls>.el-select{width:min(100%,260px)}.long-text{white-space:pre-wrap;overflow-wrap:anywhere}.detail-wide{grid-column:1/-1}.event-card{padding:14px 0;border-bottom:1px solid var(--cl-border);overflow-wrap:anywhere}.event-card p{margin:6px 0}.event-card small,.pending-note{color:var(--cl-muted)}@media(max-width:900px){.impact-panel{grid-template-columns:1fr}.impact-panel .el-button{justify-self:start}}@media(max-width:640px){.dispute-heading{align-items:flex-start;flex-direction:column}.fixture-controls>*{width:100%}}
</style>
