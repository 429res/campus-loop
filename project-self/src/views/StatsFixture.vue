<script setup>
import { computed, ref } from "vue";
import BusinessStatsPanel from "@/components/stats/BusinessStatsPanel.vue";
import { STATS_LOAD_STATE } from "@/features/stats/statsPresentation";

const scenario = ref("zero");
const state = computed(() => {
  if (scenario.value === "failure") return { stats: null, statsState: STATS_LOAD_STATE.ERROR, pendingReview: null, pendingState: STATS_LOAD_STATE.ERROR };
  if (scenario.value === "partial") return { stats: { users: 18, items: 42, availableItems: 9, recommendations: 3, recommendationsStatus: "AVAILABLE" }, statsState: STATS_LOAD_STATE.READY, pendingReview: null, pendingState: STATS_LOAD_STATE.ERROR };
  if (scenario.value === "limit") return { stats: { users: 18, items: 241, availableItems: 219, recommendations: null, recommendationsStatus: "LIMIT_EXCEEDED" }, statsState: STATS_LOAD_STATE.READY, pendingReview: 7, pendingState: STATS_LOAD_STATE.READY };
  return { stats: { users: 0, items: 0, availableItems: 0, recommendations: 0, recommendationsStatus: "AVAILABLE" }, statsState: STATS_LOAD_STATE.READY, pendingReview: 0, pendingState: STATS_LOAD_STATE.READY };
});
</script>

<template>
  <div class="page-heading"><div><span class="eyebrow">LOCAL COMPONENT FIXTURE</span><h1>业务统计展示夹具</h1><p>只验证展示语义，不调用 API，也不作为真实数据证据。</p></div><el-tag type="warning" round>仅开发环境</el-tag></div>
  <el-alert title="固定虚构数据：不会写入数据库，也不会证明后端统计正确" type="warning" :closable="false" show-icon />
  <section class="panel fixture-controls">
    <label for="stats-scenario">展示场景</label>
    <el-select id="stats-scenario" v-model="scenario" aria-label="统计夹具场景">
      <el-option label="真实零" value="zero" />
      <el-option label="推荐规模超限" value="limit" />
      <el-option label="待审量部分失败" value="partial" />
      <el-option label="全部读取失败" value="failure" />
    </el-select>
  </section>
  <BusinessStatsPanel v-bind="state" @open-records="() => {}" />
</template>

<style scoped>
.fixture-controls { display: flex; align-items: center; gap: 14px; margin-top: 18px; margin-bottom: 0; }
.fixture-controls label { font-size: 12px; font-weight: 650; }
.fixture-controls .el-select { width: 240px; }
@media (max-width: 520px) { .fixture-controls { align-items: stretch; flex-direction: column; } .fixture-controls .el-select { width: 100%; } }
</style>
