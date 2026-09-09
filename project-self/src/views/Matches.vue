<script setup>
import { ref, computed, onMounted } from "vue";
import { RefreshRight, Connection } from "@element-plus/icons-vue";
import http from "@/http";
const matches = ref([]),
  busy = ref(false),
  error = ref(false),
  kind = ref("全部方案");
const shown = computed(() =>
  matches.value.filter(
    (x) =>
      kind.value === "全部方案" ||
      x.length === (kind.value === "双方交换" ? 2 : 3),
  ),
);
async function load() {
  busy.value = true;
  error.value = false;
  try {
    matches.value = (await http.get("/api/matches")).data;
  } catch {
    error.value = true;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">MATCH NEEDS, MAKE A LOOP</span>
      <h1>
        交换推荐 <el-icon class="heading-spark"><Connection /></el-icon>
      </h1>
      <p>每一条箭头，都有真实需求作为理由。</p>
    </div>
    <el-button :icon="RefreshRight" :loading="busy" @click="load"
      >重新读取</el-button
    >
  </div>
  <div class="match-intro">
    <div>
      <b>推荐方案 ≠ 正式交换</b>
      <p>
        当前页面用于查看推荐，暂未接入正式交换操作。推荐不会占用物品，也不表示参与者已经同意。
      </p>
    </div>
    <span class="pill">规则可解释 · 无需外部 AI</span>
  </div>
  <div class="match-toolbar">
    <el-segmented
      v-model="kind"
      :options="['全部方案', '双方交换', '三方循环']"
    /><span>{{ shown.length }} 个方案</span>
  </div>
  <el-alert
    v-if="error"
    title="推荐读取失败，请重试。"
    type="error"
    :closable="false"
  />
  <div class="matches-grid" v-loading="busy">
    <article v-for="match in shown" :key="match.id" class="panel match-card">
      <header>
        <span class="match-label"
          ><span class="match-icon"><el-icon><Connection /></el-icon></span
          >{{ match.length === 2 ? "双方交换" : "三方循环" }}</span
        ><el-tag :type="match.length === 2 ? 'primary' : 'success'" round
          >匹配得分 {{ match.score }}</el-tag
        >
      </header>
      <div class="flow-list">
        <div
          v-for="(flow, index) in match.flows"
          :key="flow.itemId"
          class="flow"
        >
          <div class="flow-number">{{ index + 1 }}</div>
          <div class="flow-person">
            <span>{{ flow.fromName }}</span
            ><strong>{{ flow.itemTitle }}</strong>
          </div>
          <div class="flow-arrow"><span>提供给</span>⟶</div>
          <div class="flow-person receiver">
            <span>{{ flow.toName }}</span
            ><small>{{ flow.reason }}</small>
          </div>
        </div>
      </div>
      <p class="match-reason"><span>为什么推荐</span>{{ match.explanation }}</p>
      <footer>
        <small>物品状态以正式创建时重新校验为准</small
        ><el-button disabled>创建交换 · 待开发</el-button>
      </footer>
    </article>
    <el-empty
      v-if="!busy && !shown.length"
      description="暂未形成交换环。发布更多带需求的可交换物品后再来看看。"
      :image-size="100"
    />
  </div>
</template>
