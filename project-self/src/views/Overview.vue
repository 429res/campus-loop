<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  RefreshRight,
  ArrowRight,
} from "@element-plus/icons-vue";
import http from "@/http";
import { imageUrl } from "@/api/uploadApi";
import BusinessStatsPanel from "@/components/stats/BusinessStatsPanel.vue";
import { createLatestRequestTracker, STATS_LOAD_STATE } from "@/features/stats/statsPresentation";
const router = useRouter(),
  stats = ref(null),
  statsState = ref(STATS_LOAD_STATE.IDLE),
  pendingReview = ref(null),
  pendingState = ref(STATS_LOAD_STATE.IDLE),
  items = ref([]),
  itemsState = ref(STATS_LOAD_STATE.IDLE);
const requests = createLatestRequestTracker();
const loading = computed(() => [statsState.value, pendingState.value, itemsState.value].includes(STATS_LOAD_STATE.LOADING));
async function load() {
  const request = requests.start();
  statsState.value = pendingState.value = itemsState.value = STATS_LOAD_STATE.LOADING;
  const config = { signal: request.signal };
  await Promise.allSettled([
    http.get("/api/admin/stats", config).then(({ data }) => {
      if (!request.isLatest()) return;
      stats.value = data;
      statsState.value = STATS_LOAD_STATE.READY;
    }).catch((error) => {
      if (!request.isLatest() || error?.code === "ERR_CANCELED") return;
      stats.value = null;
      statsState.value = STATS_LOAD_STATE.ERROR;
    }),
    http.get("/api/admin/items", { ...config, params: { page: 1, size: 1, status: "PENDING_REVIEW" } }).then(({ data }) => {
      if (!request.isLatest()) return;
      pendingReview.value = data.total;
      pendingState.value = STATS_LOAD_STATE.READY;
    }).catch((error) => {
      if (!request.isLatest() || error?.code === "ERR_CANCELED") return;
      pendingReview.value = null;
      pendingState.value = STATS_LOAD_STATE.ERROR;
    }),
    http.get("/api/admin/items", { ...config, params: { page: 1, size: 4 } }).then(({ data }) => {
      if (!request.isLatest()) return;
      items.value = data.records;
      itemsState.value = STATS_LOAD_STATE.READY;
    }).catch((error) => {
      if (!request.isLatest() || error?.code === "ERR_CANCELED") return;
      items.value = [];
      itemsState.value = STATS_LOAD_STATE.ERROR;
    }),
  ]);
}
onMounted(load);
onBeforeUnmount(() => requests.cancel());
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">YOUR CAMPUS, IN A LOOP</span>
      <h1>让好东西，继续发光 <span class="heading-spark">✳</span></h1>
      <p>发现真实需求，连接校园里的每一份闲置。</p>
    </div>
    <el-button :icon="RefreshRight" :loading="loading" @click="load"
      >刷新数据</el-button
    >
  </div>
  <section class="overview-hero">
    <div>
      <span class="pill">循环，从需求开始</span>
      <h2>不止一对一交换，<br />让需求连成一个圈。</h2>
      <p>
        读懂「我有什么」与「我想要什么」，<br />发现双方交换和三方循环的更多可能。
      </p>
      <el-button type="primary" size="large" @click="router.push('/matches')"
        >探索交换推荐<el-icon><ArrowRight /></el-icon
      ></el-button>
    </div>
    <div
      class="hero-diagram"
      aria-label="虚构示例：书籍交换相机，相机交换台灯，台灯交换书籍"
    >
      <div class="orbit" />
      <span class="orbit-word">EVERYTHING<br /><b>COMES AROUND.</b></span>
      <div class="mini-item mini-book">
        <img src="/demo/book.svg" alt="书籍" /><span>书籍</span>
      </div>
      <div class="mini-item mini-camera">
        <img src="/demo/camera.svg" alt="相机" /><span>相机</span>
      </div>
      <div class="mini-item mini-lamp">
        <img src="/demo/lamp.svg" alt="台灯" /><span>台灯</span>
      </div>
      <span class="orbit-arrow first">↘</span
      ><span class="orbit-arrow second">↙</span
      ><span class="orbit-arrow third">↑</span
      ><small>概念示意 · 虚构物品</small>
    </div>
  </section>
  <BusinessStatsPanel
    :stats="stats"
    :stats-state="statsState"
    :pending-review="pendingReview"
    :pending-state="pendingState"
    @open-records="router.push($event)"
  />
  <el-alert v-if="itemsState === STATS_LOAD_STATE.ERROR" title="最近物品读取失败；统计卡片仍保留各自的真实读取结果。" type="error" :closable="false" show-icon />
  <section class="panel">
    <div class="section-heading">
      <div>
        <h2>最近发布</h2>
        <p>来自当前开发数据库的真实记录</p>
      </div>
      <el-button text @click="router.push('/items')"
        >查看全部<el-icon><ArrowRight /></el-icon
      ></el-button>
    </div>
    <div v-if="items.length" class="recent-grid">
      <button
        v-for="item in items"
        :key="item.id"
        class="recent-item"
        @click="router.push({ path: '/items', query: { id: item.id } })"
      >
        <div class="item-image">
          <img
            :src="imageUrl(item.imageUrl) || '/demo/book.svg'"
            :alt="item.title"
          /><span>{{ item.categoryName }}</span>
        </div>
        <div class="recent-copy">
          <h3>{{ item.title }}</h3>
          <p>想换 · {{ item.wantedCategoryName || "待补充需求" }}</p>
          <div class="owner">
            <span class="avatar">{{
              (item.ownerName || "同学").slice(0, 1)
            }}</span
            >{{ item.ownerName
            }}<span class="item-state">{{
              item.status === "AVAILABLE" ? "可交换" : item.status
            }}</span>
          </div>
        </div>
      </button>
    </div>
    <el-empty
      v-else-if="itemsState === STATS_LOAD_STATE.READY"
      description="还没有物品，去用户端发布第一件闲置吧"
      :image-size="74"
    />
  </section>
  <section class="principles">
    <div>
      <span>01</span>
      <h3>需求有方向</h3>
      <p>以分类、标签与需求规则建立可解释的连接。</p>
    </div>
    <div>
      <span>02</span>
      <h3>推荐不占用</h3>
      <p>浏览方案不会锁定物品；正式交换与推荐数分别统计。</p>
    </div>
    <div>
      <span>03</span>
      <h3>履历可溯源</h3>
      <p>区分用户自述、参与者确认与管理员核验。</p>
    </div>
  </section>
</template>
