<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRoute } from "vue-router";
import { Search, RefreshRight } from "@element-plus/icons-vue";
import http from "@/http";
import { imageUrl } from "@/api/uploadApi";
import { useOverlayLock } from "@/composables/useOverlayLock";
const route = useRoute(),
  filters = reactive({ keyword: "", categoryId: null, page: 1, size: 12 }),
  items = ref([]),
  total = ref(0),
  categories = ref([]),
  busy = ref(false),
  detail = ref(null),
  drawer = ref(false),
  error = ref(false);
let sequence = 0;
useOverlayLock(drawer);
async function load() {
  const current = ++sequence;
  busy.value = true;
  error.value = false;
  try {
    const { data } = await http.get("/api/admin/items", { params: filters });
    if (current !== sequence) return;
    items.value = data.records;
    total.value = data.total;
  } catch {
    if (current === sequence) error.value = true;
  } finally {
    if (current === sequence) busy.value = false;
  }
}
function search() {
  filters.page = 1;
  load();
}
function reset() {
  filters.keyword = "";
  filters.categoryId = null;
  search();
}
async function show(item) {
  try {
    const { data } = await http.get(`/api/items/${item.id}`);
    detail.value = data;
    drawer.value = true;
  } catch {}
}
onMounted(async () => {
  load();
  try {
    categories.value = (await http.get("/api/categories")).data;
  } catch {}
  if (route.query.id) show({ id: route.query.id });
});
const statusLabel = (value) =>
  ({
    AVAILABLE: "可交换",
    RESERVED: "交换中",
    EXCHANGED: "已交换",
    WITHDRAWN: "已下架",
  })[value] || value;
</script>
<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">CAMPUS COLLECTION</span>
      <h1>物品管理</h1>
      <p>清楚了解每件物品、发布者和交换需求。</p>
    </div>
    <span class="count-pill">共 {{ total }} 件物品</span>
  </div>
  <section class="panel">
    <form class="filter-bar" @submit.prevent="search">
      <el-input
        v-model="filters.keyword"
        aria-label="搜索物品"
        clearable
        placeholder="搜索物品名称或描述"
        :prefix-icon="Search"
      /><el-select
        v-model="filters.categoryId"
        aria-label="物品分类"
        clearable
        placeholder="全部分类"
        ><el-option
          v-for="cat in categories"
          :key="cat.id"
          :value="cat.id"
          :label="cat.name" /></el-select
      ><el-button type="primary" native-type="submit" :loading="busy"
        >搜索</el-button
      ><el-button :icon="RefreshRight" @click="reset">重置</el-button>
    </form>
    <el-alert
      v-if="error"
      title="物品读取失败，请重试。"
      type="error"
      :closable="false"
    /><el-table :data="items" v-loading="busy" row-key="id" style="width: 100%"
      ><el-table-column label="物品信息" min-width="275"
        ><template #default="{ row }"
          ><div class="table-item">
            <img
              :src="imageUrl(row.imageUrl) || '/demo/book.svg'"
              :alt="row.title"
            />
            <div>
              <strong>{{ row.title }}</strong
              ><small>{{ row.categoryName }} · {{ row.conditionLevel }}</small>
            </div>
          </div></template
        ></el-table-column
      ><el-table-column
        prop="ownerName"
        label="发布者"
        min-width="110" /><el-table-column label="交换需求" min-width="160"
        ><template #default="{ row }"
          ><span class="want-label">{{
            row.wantedCategoryName || "未设置"
          }}</span
          ><small class="table-sub">{{
            (row.wantedTags || []).join(" · ") || "分类匹配"
          }}</small></template
        ></el-table-column
      ><el-table-column label="状态" min-width="115"
        ><template #default="{ row }"
          ><el-tag
            :type="row.status === 'AVAILABLE' ? 'success' : 'info'"
            effect="light"
            round
            >{{ statusLabel(row.status) }}</el-tag
          ></template
        ></el-table-column
      ><el-table-column label="发布时间" min-width="165"
        ><template #default="{ row }">{{
          row.createdAt?.replace("T", " ").slice(0, 16) || "—"
        }}</template></el-table-column
      ><el-table-column label="操作" width="85" fixed="right"
        ><template #default="{ row }"
          ><el-button type="primary" link @click="show(row)"
            >详情</el-button
          ></template
        ></el-table-column
      ><template #empty
        ><el-empty
          description="没有找到物品，试试其他关键词"
          :image-size="72" /></template
    ></el-table>
    <div class="pagination-row">
      <span>每件物品都在等待下一段故事</span
      ><el-pagination
        v-model:current-page="filters.page"
        :page-size="filters.size"
        :total="total"
        :pager-count="5"
        layout="prev, pager, next"
        background
        @current-change="load"
      />
    </div>
  </section>
  <el-drawer
    :lock-scroll="false"
    v-model="drawer"
    title="物品详情"
    size="min(520px, 100vw)"
    destroy-on-close
    ><template v-if="detail"
      ><img
        class="detail-image"
        :src="imageUrl(detail.imageUrl) || '/demo/book.svg'"
        :alt="detail.title" />
      <div class="detail-tags">
        <el-tag round>{{ detail.categoryName }}</el-tag
        ><el-tag type="success" round>{{ statusLabel(detail.status) }}</el-tag>
      </div>
      <h2>{{ detail.title }}</h2>
      <p class="detail-description">{{ detail.description }}</p>
      <dl class="detail-data">
        <div>
          <dt>发布者</dt>
          <dd>{{ detail.ownerName }}</dd>
        </div>
        <div>
          <dt>成色</dt>
          <dd>{{ detail.conditionLevel }}</dd>
        </div>
        <div>
          <dt>物品标签</dt>
          <dd>{{ (detail.tags || []).join("、") || "无" }}</dd>
        </div>
        <div>
          <dt>想要交换</dt>
          <dd>
            {{ detail.wantedCategoryName || "未设置" }} ·
            {{ (detail.wantedTags || []).join("、") || "不限标签" }}
          </dd>
        </div>
        <div>
          <dt>记录编号</dt>
          <dd>#{{ detail.id }}</dd>
        </div>
      </dl>
      <el-alert
        title="审核、下架和履历核验将在后续业务迭代接入。当前仅展示数据库记录。"
        type="info"
        :closable="false"
        show-icon /></template
  ></el-drawer>
</template>
