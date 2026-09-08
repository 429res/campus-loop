<script setup>
import { computed, onBeforeUnmount, onMounted, toRef, watch } from "vue";
import { RefreshRight } from "@element-plus/icons-vue";
import http from "@/http";
import { SESSION_KEY, useAuth } from "@/stores/auth";
import { useOverlayLock } from "@/composables/useOverlayLock";
import { createAdminExchangeReader, exchangeStatuses, statusLabel, statusType, eventLabel, formatExchangeTime } from "@/features/exchanges/adminExchangeReader";

const auth = useAuth();
const reader = createAdminExchangeReader({
  get: (url, options) => http.get(url, options),
  readSession: () => auth.token === sessionStorage.getItem(SESSION_KEY) ? auth.token : "",
});
const { state } = reader;
const exchange = computed(() => state.detail?.exchange);
const creation = computed(() => state.detail?.creation);
const events = computed(() => state.detail?.events || []);
const personName = (record, id) => record?.participants.find(person => person.userId === id)?.displayName || `用户 #${id}`;
const itemTitle = id => creation.value?.flows.find(flow => flow.itemId === id)?.itemTitle || `物品 #${id}`;
const localZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

useOverlayLock(toRef(state, "drawer"));
function filterChanged() { reader.closeDetail(); reader.loadList(1); }
function changePage(page) { if (state.busy || state.error || page === state.page) return; reader.closeDetail(); reader.loadList(page); }
function updateDrawer(open) { if (!open) reader.closeDetail(); }
watch(() => auth.token, () => {
  reader.reset();
  // Login updates the Pinia token before its sessionStorage write.
  Promise.resolve().then(() => { if (auth.token) reader.loadList(); });
}, { flush: "sync" });
onMounted(() => reader.loadList());
onBeforeUnmount(() => reader.dispose());
</script>

<template>
  <div class="page-heading exchange-heading">
    <div><span class="eyebrow">FOLLOW EVERY LOOP</span><h1>交换记录</h1><p>查看同学之间的物品流向、交换进度和处理记录。</p></div>
    <el-tag round effect="plain">管理只读</el-tag>
  </div>
  <section class="panel exchange-list-panel">
    <div class="exchange-toolbar">
      <el-select v-model="state.status" aria-label="交换状态" clearable placeholder="全部状态" @change="filterChanged">
        <el-option v-for="status in exchangeStatuses" :key="status.value" :label="status.label" :value="status.value" />
      </el-select>
      <el-button :icon="RefreshRight" :loading="state.busy" @click="reader.loadList()">重新读取</el-button>
    </div>
    <p class="exchange-caption">确认邀请和交接由参与者本人操作，管理员在此查看记录。时间按 {{ localZone }} 显示。</p>
    <div v-if="state.busy" role="status" aria-label="正在读取交换记录"><el-skeleton :rows="5" animated /></div>
    <div v-else-if="state.error" class="exchange-error" role="alert">
      <el-alert :title="state.error" type="error" :closable="false" show-icon />
      <el-button @click="reader.loadList()">重试列表</el-button>
    </div>
    <el-table v-else :data="state.records" row-key="id" class="exchange-table">
      <el-table-column label="交换" min-width="145">
        <template #default="{ row }"><strong>#{{ row.id }}</strong><small class="table-sub">{{ row.participants.length }} 人交换</small></template>
      </el-table-column>
      <el-table-column label="参与者" min-width="210">
        <template #default="{ row }"><span class="exchange-names">{{ row.participants.map(person => person.displayName).join(' · ') }}</span><small class="table-sub">发起人：{{ personName(row, row.initiatorId) }}</small></template>
      </el-table-column>
      <el-table-column label="当前进度" min-width="125">
        <template #default="{ row }"><el-tag :type="statusType(row.status)" round>{{ statusLabel(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="180">
        <template #default="{ row }">{{ formatExchangeTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="查看" width="105" fixed="right">
        <template #default="{ row }"><el-button type="primary" link :aria-label="`查看交换 #${row.id} 详情`" @click="reader.openDetail(row.id)">查看详情</el-button></template>
      </el-table-column>
      <template #empty><el-empty :description="state.status ? '没有符合此状态的交换记录' : '还没有交换记录'" :image-size="80" /></template>
    </el-table>
    <div class="pagination-row exchange-pagination">
      <span>{{ state.busy ? '正在读取…' : state.error ? '读取失败' : `共 ${state.total} 条交换` }}</span>
      <el-pagination :current-page="state.page" :page-size="state.size" :total="state.total" :disabled="state.busy || !!state.error" :pager-count="5" layout="prev, pager, next" background @current-change="changePage" />
    </div>
  </section>

  <el-drawer :model-value="state.drawer" :title="`交换 #${state.detailId ?? ''} 详情`" size="min(820px, 100vw)" :lock-scroll="false" destroy-on-close @update:model-value="updateDrawer">
    <div class="exchange-drawer">
      <div class="exchange-detail-toolbar"><span class="exchange-caption">管理只读 · {{ localZone }}</span><el-button :icon="RefreshRight" :loading="state.detailBusy" @click="reader.openDetail()">刷新详情</el-button></div>
      <div v-if="state.detailBusy" role="status" aria-label="正在读取交换详情"><el-skeleton :rows="8" animated /></div>
      <div v-else-if="state.detailError" class="exchange-error" role="alert"><el-alert :title="state.detailError" type="error" :closable="false" show-icon /><el-button @click="reader.openDetail()">重试详情</el-button></div>
      <template v-else-if="exchange">
        <section class="exchange-summary">
          <div class="exchange-summary-title"><h2>{{ exchange.participants.length }} 人交换</h2><el-tag :type="statusType(exchange.status)" round>{{ statusLabel(exchange.status) }}</el-tag></div>
          <dl class="exchange-facts">
            <div><dt>发起人</dt><dd>{{ personName(exchange, exchange.initiatorId) }}</dd></div>
            <div><dt>记录版本</dt><dd>{{ exchange.version }}</dd></div>
            <div><dt>创建时间</dt><dd>{{ formatExchangeTime(exchange.createdAt) }}</dd></div>
            <div><dt>原定截止</dt><dd>{{ formatExchangeTime(exchange.expiresAt) }}</dd></div>
          </dl>
          <p class="exchange-caption">进度以本次读取结果为准。交接开始后，原定截止时间不会触发自动取消。</p>
        </section>

        <section aria-labelledby="exchange-participants-title">
          <h3 id="exchange-participants-title">参与者与交接记录</h3>
          <div class="exchange-people">
            <article v-for="person in exchange.participants" :key="person.userId" class="exchange-person">
              <div class="exchange-person-heading"><strong>{{ person.displayName }}</strong><span class="exchange-caption">用户 #{{ person.userId }}</span></div>
              <p class="exchange-item-pair">提供 #{{ person.offeredItemId }} · 接收 #{{ person.receivedItemId }}</p>
              <dl class="exchange-facts">
                <div><dt>邀请确认</dt><dd>{{ person.confirmationStatus === 'CONFIRMED' ? '已确认' : '未确认' }}<small v-if="person.confirmedAt">{{ formatExchangeTime(person.confirmedAt) }}</small></dd></div>
                <div><dt>已交出声明</dt><dd>{{ formatExchangeTime(person.handedOffAt) }}</dd></div>
                <div><dt>已收到声明</dt><dd>{{ formatExchangeTime(person.receivedAt) }}</dd></div>
              </dl>
            </article>
          </div>
        </section>

        <section aria-labelledby="exchange-flows-title">
          <h3 id="exchange-flows-title">物品流向</h3>
          <ol class="exchange-flow-list">
            <li v-for="flow in exchange.flows" :key="flow.itemId"><div class="exchange-flow-direction"><strong>{{ personName(exchange, flow.fromUserId) }}</strong><span aria-label="提供给">→</span><strong>{{ personName(exchange, flow.toUserId) }}</strong></div><p>{{ itemTitle(flow.itemId) }}<span v-if="creation" class="exchange-caption"> · #{{ flow.itemId }} · 创建时名称</span></p></li>
          </ol>
        </section>

        <section aria-labelledby="exchange-creation-title">
          <h3 id="exchange-creation-title">创建时的匹配依据</h3>
          <el-alert v-if="!creation" title="此记录没有可用的历史创建快照，无法展示当时物品名称与匹配依据。" type="info" :closable="false" show-icon />
          <template v-else><p class="exchange-caption">规则 {{ creation.ruleVersion }} · 以下内容保留创建时的匹配结果。</p><div class="exchange-creation-list"><article v-for="flow in creation.flows" :key="flow.itemId"><strong>{{ flow.itemTitle }} · #{{ flow.itemId }}</strong><p>{{ personName(exchange, flow.fromUserId) }} → {{ personName(exchange, flow.toUserId) }}</p><p>满足需求 #{{ flow.demandId }} · {{ flow.matchedCategoryName }}</p><p class="exchange-caption">{{ flow.reason }}</p></article></div></template>
        </section>

        <section aria-labelledby="exchange-events-title">
          <h3 id="exchange-events-title">处理时间线</h3>
          <el-alert v-if="!events.length" title="此记录没有已保存的处理事件。未补造历史确认或交接记录。" type="info" :closable="false" show-icon />
          <el-timeline v-else class="exchange-timeline">
            <el-timeline-item v-for="event in events" :key="event.id" :timestamp="formatExchangeTime(event.occurredAt)" placement="top" :type="statusType(event.newStatus)">
              <strong>{{ eventLabel(event.eventType) }}</strong>
              <p>{{ event.actorDisplayName || (event.actorId == null ? '系统' : `用户 #${event.actorId}`) }} · {{ statusLabel(event.previousStatus) }} → {{ statusLabel(event.newStatus) }}</p>
              <p v-if="event.reason" class="exchange-event-reason">{{ event.reason }}</p>
              <span class="exchange-caption">记录版本 {{ event.previousVersion }} → {{ event.newVersion }}</span>
            </el-timeline-item>
          </el-timeline>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.exchange-list-panel,.exchange-drawer{min-width:0}.exchange-toolbar,.exchange-detail-toolbar,.exchange-summary-title,.exchange-person-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.exchange-toolbar{justify-content:flex-start;margin-bottom:12px}.exchange-toolbar .el-select{width:220px;max-width:100%}.exchange-caption{color:var(--cl-muted);font-size:12px;line-height:1.8;overflow-wrap:anywhere}.exchange-list-panel>.exchange-caption{margin:0 0 22px}.exchange-names{overflow-wrap:anywhere}.exchange-error{display:flex;flex-direction:column;align-items:flex-start;gap:14px;padding:14px 0}.exchange-table{width:100%}.exchange-drawer{color:var(--cl-text)}.exchange-drawer>section{margin-top:26px}.exchange-drawer h2,.exchange-drawer h3{margin:0 0 15px}.exchange-summary-title h2{margin:0}.exchange-summary,.exchange-person,.exchange-flow-list>li,.exchange-creation-list>article{background:var(--cl-surface);border:1px solid var(--cl-border);border-radius:14px;padding:18px}.exchange-facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 20px;margin:18px 0 0}.exchange-facts div{min-width:0}.exchange-facts dt{font-size:12px;color:var(--cl-muted);margin-bottom:5px}.exchange-facts dd{font-size:13px;line-height:1.7;margin:0;overflow-wrap:anywhere}.exchange-facts small{display:block;color:var(--cl-muted)}.exchange-people,.exchange-creation-list{display:grid;gap:12px}.exchange-person-heading{align-items:flex-start;flex-wrap:wrap}.exchange-person-heading strong{overflow-wrap:anywhere;min-width:0}.exchange-item-pair{margin:8px 0 0;color:var(--cl-blue);font-size:12px}.exchange-flow-list{display:grid;gap:12px;padding:0;list-style:none}.exchange-flow-direction{display:flex;align-items:center;flex-wrap:wrap;gap:12px;overflow-wrap:anywhere}.exchange-flow-direction>span{color:var(--cl-primary);font-size:20px}.exchange-flow-list p,.exchange-creation-list p{font-size:13px;line-height:1.8;overflow-wrap:anywhere;margin:6px 0 0}.exchange-creation-list strong{overflow-wrap:anywhere}.exchange-timeline{padding-left:8px}.exchange-timeline p{font-size:13px;line-height:1.8;margin:6px 0;overflow-wrap:anywhere}.exchange-event-reason{white-space:pre-wrap}.exchange-summary>.exchange-caption{margin:12px 0 0}@media(max-width:640px){.exchange-heading{align-items:flex-start;gap:16px}.exchange-toolbar{flex-wrap:wrap}.exchange-toolbar .el-select{flex:1;min-width:150px}.exchange-pagination{flex-direction:column;align-items:flex-start;gap:14px}.exchange-detail-toolbar{align-items:flex-start;flex-wrap:wrap}.exchange-facts{grid-template-columns:1fr}.exchange-summary,.exchange-person,.exchange-flow-list>li,.exchange-creation-list>article{padding:15px}}
</style>
