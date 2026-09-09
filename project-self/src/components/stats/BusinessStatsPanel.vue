<script setup>
import { computed } from "vue";
import { Box, Connection, DocumentChecked, RefreshRight, User } from "@element-plus/icons-vue";
import { metricPresentation, STATS_LOAD_STATE } from "@/features/stats/statsPresentation";

const props = defineProps({
  stats: { type: Object, default: null },
  statsState: { type: String, default: STATS_LOAD_STATE.IDLE },
  pendingReview: { type: Number, default: null },
  pendingState: { type: String, default: STATS_LOAD_STATE.IDLE },
});
const emit = defineEmits(["open-records"]);

const metrics = computed(() => [
  {
    key: "items", label: "物品记录", icon: Box, color: "pink", source: "stats",
    note: "全部物品，包含已下架与历史记录",
  },
  {
    key: "users", label: "账号记录", icon: User, color: "blue", source: "stats",
    note: "全部账号，包含已停用账号",
  },
  {
    key: "availableItems", label: "可交换物品", icon: RefreshRight, color: "green", source: "stats",
    note: "仅按物品状态统计，不等同匹配候选",
  },
  {
    key: "pendingReview", label: "待审核物品", icon: DocumentChecked, color: "orange", source: "pending",
    note: "等待审核后上架的物品",
    route: "/items?status=PENDING_REVIEW",
  },
  {
    key: "recommendations", label: "交换推荐", icon: Connection, color: "blue", source: "stats",
    note: "当前 2/3 人候选环；不是正式或完成交换",
  },
]);

function presentation(metric) {
  const state = metric.source === "pending" ? props.pendingState : props.statsState;
  const value = metric.source === "pending" ? props.pendingReview : props.stats?.[metric.key];
  return metricPresentation({
    state,
    value,
    unavailable: metric.key === "recommendations" && props.statsState === STATS_LOAD_STATE.READY && props.stats?.recommendationsStatus === "LIMIT_EXCEEDED",
  });
}
function note(metric, shown) {
  if (shown.state === "error") return "该指标请求失败，未按 0 展示";
  if (shown.state === "loading") return "正在读取服务端统计";
  if (shown.state === "unavailable") return "候选或方案超出上限，暂不可统计";
  return metric.note;
}
</script>

<template>
  <section class="business-stats" aria-labelledby="business-stats-title">
    <div class="section-heading stats-heading">
      <div>
        <h2 id="business-stats-title">校园循环概况</h2>
        <p>当前全量数据；各指标独立更新。</p>
      </div>
      <el-tag type="info" round>无趋势数据</el-tag>
    </div>
    <div class="stats-grid">
      <article v-for="metric in metrics" :key="metric.key" class="stat-card" :class="`is-${presentation(metric).state}`">
        <div class="stat-top">
          <span>{{ metric.label }}</span>
          <el-icon :class="metric.color"><component :is="metric.icon" /></el-icon>
        </div>
        <strong :aria-label="`${metric.label}：${presentation(metric).value}`">{{ presentation(metric).value }}</strong>
        <small>{{ note(metric, presentation(metric)) }}</small>
        <el-button v-if="metric.route" text size="small" @click="emit('open-records', metric.route)">查看待审记录</el-button>
      </article>
    </div>
    <details class="stats-methodology">
      <summary>统计口径与暂不可用指标</summary>
      <div class="methodology-copy">
        <p>以上均为当前全量读数：账号和物品按数据库记录 ID 计数；AVAILABLE 与待审核按当前物品状态计数。空集合返回真实 0。</p>
        <p>legacy-v1 推荐按当次计算后旋转去重的候选环计数，读取不创建交换或占用；规模超限返回不可用，不是 0。</p>
        <p>完成交换、状态分布和按日期趋势尚无管理员统计接口，当前不展示。未来期间完成量应按交换 ID 去重，并以 UTC 的 COMPLETED 事件时间使用左闭右开区间。</p>
      </div>
    </details>
  </section>
</template>

<style scoped>
.business-stats { margin: 23px 0; }
.stats-heading { margin-bottom: 13px; }
.stats-grid { margin: 0; grid-template-columns: repeat(5, minmax(0, 1fr)); }
.stat-card { min-height: 158px; display: flex; flex-direction: column; }
.stat-card small { line-height: 1.55; min-height: 29px; }
.stat-card .el-button { align-self: flex-start; margin: 7px 0 -7px -11px; }
.stat-card.is-error strong { color: var(--cl-danger); font-size: 18px; letter-spacing: 0; margin-top: 16px; }
.stat-card.is-unavailable strong { color: var(--cl-warning); }
.stats-methodology { border: 1px solid var(--cl-border); background: var(--cl-surface); border-radius: 14px; margin-top: 12px; padding: 13px 16px; }
.stats-methodology summary { cursor: pointer; font-size: 12px; font-weight: 650; color: var(--cl-text); }
.methodology-copy { margin-top: 10px; }
.methodology-copy p { margin: 5px 0; color: var(--cl-muted); font-size: 11px; line-height: 1.65; }
@media (max-width: 1180px) { .stats-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 760px) { .stats-grid { grid-template-columns: 1fr 1fr; } }
@media (max-width: 480px) { .stats-grid { grid-template-columns: 1fr; } .stats-heading { align-items: flex-start; } }
.stat-top { gap:12px; align-items:flex-start; }.stat-top .el-icon { flex:none; }.stat-card small { font-size:12px; min-height:38px; }.stats-heading p {font-size:13px}.stat-card .el-button {margin:8px 0 0; padding:0; min-height:32px;}
</style>
