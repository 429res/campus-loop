<script setup>
import { computed, onMounted, ref } from "vue";
import { Edit, Plus, RefreshRight, Search } from "@element-plus/icons-vue";
import CategoryFormDialog from "@/components/categories/CategoryFormDialog.vue";
import http from "@/http";

const PAGE_SIZE = 10;
const showFixture = import.meta.env.DEV;
const categories = ref([]);
const keyword = ref("");
const page = ref(1);
const loading = ref(false);
const readError = ref(false);
const fixtureVisible = ref(false);
const fixtureCategory = ref(null);
const fixtureOutcome = ref("preview");
const fixtureResult = ref("");
const conflictRefreshes = ref(0);
let requestSequence = 0;
const pendingFixture = ref(null);

const filtered = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase();
  if (!needle) return categories.value;
  return categories.value.filter((category) =>
    category.name.toLocaleLowerCase().includes(needle),
  );
});
const visibleCategories = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE;
  return filtered.value.slice(start, start + PAGE_SIZE);
});

async function load({ preservePage = true } = {}) {
  const current = ++requestSequence;
  loading.value = true;
  readError.value = false;
  try {
    const { data } = await http.get("/api/categories", { params: { includeInactive: true } });
    if (current !== requestSequence) return;
    categories.value = data;
    const lastPage = Math.max(1, Math.ceil(filtered.value.length / PAGE_SIZE));
    page.value = preservePage ? Math.min(page.value, lastPage) : 1;
  } catch {
    if (current === requestSequence) readError.value = true;
  } finally {
    if (current === requestSequence) loading.value = false;
  }
}

function search() {
  page.value = 1;
}

function reset() {
  keyword.value = "";
  page.value = 1;
}

function fixtureError(status) {
  const error = new Error("fixture");
  error.status = status;
  return error;
}

function fixtureSubmit(payload) {
  fixtureResult.value = "";
  if (fixtureOutcome.value === "conflict")
    return Promise.reject(fixtureError(409));
  if (fixtureOutcome.value === "forbidden")
    return Promise.reject(fixtureError(403));
  if (fixtureOutcome.value === "failure")
    return Promise.reject(fixtureError(503));
  if (fixtureOutcome.value === "pending")
    return new Promise((resolve) => {
      pendingFixture.value = () => resolve({ fixture: true, payload });
    });
  return Promise.resolve({ fixture: true, payload });
}

function fixtureSaved({ payload }) {
  fixtureResult.value = `已捕获请求体：${JSON.stringify(payload)}；未调用 API，未写入数据库。`;
  fixtureVisible.value = false;
  pendingFixture.value = null;
}

function openFixture(category = null) {
  fixtureCategory.value = category;
  fixtureVisible.value = true;
}

function finishPendingFixture() {
  pendingFixture.value?.();
  pendingFixture.value = null;
}

async function handleFixtureConflict() {
  conflictRefreshes.value += 1;
  await load({ preservePage: true });
}

onMounted(() => load());
</script>

<template>
  <div class="page-heading category-heading">
    <div>
      <span class="eyebrow">CATEGORY DIRECTORY</span>
      <h1>分类维护</h1>
      <p>可查看全部分类、顺序和可用状态；维护操作暂未开放。</p>
    </div>
    <el-tooltip content="维护操作暂未开放" placement="bottom">
      <span class="disabled-action-wrap">
        <el-button type="primary" :icon="Plus" disabled>新增分类</el-button>
      </span>
    </el-tooltip>
  </div>

  <el-alert
    class="category-contract-alert"
    title="分类目录预览"
    description="停用分类保留历史记录的分类名称，不能用于新的发布或需求选择。"
    type="warning"
    :closable="false"
    show-icon
  />

  <section class="panel">
    <form class="filter-bar" @submit.prevent="search">
      <el-input
        v-model="keyword"
        aria-label="搜索分类"
        clearable
        :prefix-icon="Search"
        placeholder="按分类名称筛选"
        @clear="reset"
      />
      <el-button type="primary" native-type="submit">筛选</el-button>
      <el-button :icon="RefreshRight" :loading="loading" @click="load()"
        >刷新</el-button
      >
    </form>
    <el-alert
      v-if="readError"
      title="分类读取失败，当前列表未被替换。"
      type="error"
      :closable="false"
      show-icon
    />
    <el-table
      v-loading="loading"
      :data="visibleCategories"
      row-key="id"
      style="width: 100%"
    >
      <el-table-column prop="id" label="编号" width="110" />
      <el-table-column prop="name" label="分类名称" min-width="220" />
      <el-table-column label="排序 / 可用状态" min-width="180">
        <template #default="{ row }">
          <span class="muted-cell">{{ row.sortOrder ?? 0 }} · {{ row.status === "INACTIVE" ? "已停用" : "可用" }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default>
          <el-tooltip content="维护操作暂未开放" placement="top">
            <span class="disabled-action-wrap">
              <el-button type="primary" link :icon="Edit" disabled
                >编辑</el-button
              >
            </span>
          </el-tooltip>
          <el-tooltip content="维护操作暂未开放" placement="top">
            <span class="disabled-action-wrap">
              <el-button type="danger" link disabled>停用/删除</el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="readError ? '读取失败，请刷新重试' : '没有匹配的分类'"
          :image-size="72"
        />
      </template>
    </el-table>
    <div class="pagination-row">
      <span>当前 {{ filtered.length }} 个分类 · 前端分页不改变读取契约</span>
      <el-pagination
        v-model:current-page="page"
        :page-size="PAGE_SIZE"
        :total="filtered.length"
        :pager-count="5"
        layout="prev, pager, next"
        background
      />
    </div>
  </section>

  <section v-if="showFixture" class="panel category-fixture">
    <div class="section-heading fixture-heading">
      <div>
        <span class="eyebrow">LOCAL COMPONENT FIXTURE</span>
        <h2>分类表单组件夹具</h2>
        <p>仅在开发环境显示，不调用分类写接口，不产生成功业务记录。</p>
      </div>
      <el-tag type="warning" round>非正式入口</el-tag>
    </div>
    <div class="fixture-controls">
      <el-select v-model="fixtureOutcome" aria-label="夹具响应场景">
        <el-option label="捕获请求体（不写入）" value="preview" />
        <el-option label="409 冲突并回读列表" value="conflict" />
        <el-option label="403 权限不足" value="forbidden" />
        <el-option label="请求失败" value="failure" />
        <el-option label="保持处理中（验证防重入）" value="pending" />
      </el-select>
      <el-button @click="openFixture()">打开新增表单夹具</el-button>
      <el-button
        @click="openFixture(categories[0] || { id: 'fixture', name: '夹具分类' })"
        >打开编辑表单夹具</el-button
      >
    </div>
    <el-alert
      v-if="fixtureResult"
      :title="fixtureResult"
      type="info"
      :closable="false"
    />
    <p v-if="conflictRefreshes" class="fixture-status">
      409 已触发真实 GET 回读 {{ conflictRefreshes }} 次；未覆盖原记录。
    </p>
  </section>

  <CategoryFormDialog
    :visible="fixtureVisible"
    :category="fixtureCategory"
    fixture
    :submit-request="fixtureSubmit"
    @close="fixtureVisible = false"
    @saved="fixtureSaved"
    @conflict="handleFixtureConflict"
  >
    <template #fixture-controls>
      <el-button
        v-if="fixtureOutcome === 'pending'"
        class="fixture-complete"
        :disabled="!pendingFixture"
        @click="finishPendingFixture"
        >完成夹具请求</el-button
      >
    </template>
  </CategoryFormDialog>
</template>

<style scoped>
.category-contract-alert {
  margin-bottom: 20px;
}
.disabled-action-wrap {
  display: inline-flex;
}
.muted-cell,
.fixture-status {
  color: var(--cl-muted);
  font-size: 12px;
}
.fixture-heading {
  align-items: flex-start;
}
.fixture-controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.fixture-controls .el-select {
  width: min(100%, 260px);
}
.fixture-status {
  margin: 14px 0 0;
}
.fixture-complete {
  margin-top: 12px;
}
@media (max-width: 640px) {
  .category-heading {
    flex-direction: column;
  }
  .fixture-controls > * {
    width: 100%;
  }
}
</style>
