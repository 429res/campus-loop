<script setup>
import {ElMessage,ElMessageBox} from "element-plus";
import AssistantReview from "@/components/AssistantReview.vue";
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { Check, Close, RefreshRight, Search } from "@element-plus/icons-vue";
import ReviewDecisionDialog from "@/components/reviews/ReviewDecisionDialog.vue";
import http from "@/http";
import { imageUrl } from "@/api/uploadApi";
import { useOverlayLock } from "@/composables/useOverlayLock";

const route = useRoute();
const spotlights=ref([]),spotlightsOpen=ref(false),spotlightBusy=ref(false);
async function readSpotlights(){spotlights.value=(await http.get('/api/admin/items/spotlights')).data}
async function expose(item){try{const {value}=await ElMessageBox.prompt('排序数字越小越靠前。最多展示 8 件当前可交换物品。','加入首页曝光',{inputValue:'0',inputValidator:v=>/^\d{1,3}$/.test(v)||'请输入 0–999'});await http.put(`/api/admin/items/${item.id}/spotlight`,{enabled:true,sortOrder:Number(value)});ElMessage.success('已加入首页曝光');await readSpotlights()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e.response?.data?.msg||e.message)}}
async function removeSpotlight(row){spotlightBusy.value=true;try{await http.put(`/api/admin/items/${row.itemId}/spotlight`,{enabled:false,sortOrder:0});await readSpotlights()}finally{spotlightBusy.value=false}}
async function manageSpotlights(){spotlightsOpen.value=true;await readSpotlights()}


const showFixture = import.meta.env.DEV && route.path === "/fixtures/item-review";
const filters = reactive({ keyword: "", categoryId: null, status: "", page: 1, size: 12 });
const items = ref([]);
const total = ref(0);
const categories = ref([]);
const busy = ref(false);
const detail = ref(null);
const audits = ref([]), auditTotal = ref(0), auditPage = ref(1), detailError = ref("");
let detailSequence = 0, openSequence = 0, reviewSequence = 0;
const drawer = ref(false);
const error = ref(false);
const reviewVisible = ref(false);
const reviewTarget = ref(null);
const reviewDecision = ref("APPROVE");
const fixtureOutcome = ref("preview");
const fixtureResult = ref("");
const conflictRefreshes = ref(0);
const pendingFixture = ref(null);
let sequence = 0;

const pendingCount = computed(
  () => items.value.filter((item) => item.status === "PENDING_REVIEW").length,
);

useOverlayLock(drawer);

async function load({ preservePage = true } = {}) {
  const current = ++sequence;
  busy.value = true;
  error.value = false;
  try {
    const { data } = await http.get("/api/admin/items", { params: filters });
    if (current !== sequence) return;
    items.value = data.records;
    total.value = data.total;
    if (!preservePage) filters.page = 1;
    if (detail.value) {
      const refreshed = data.records.find((item) => item.id === detail.value.id);
      if (refreshed) detail.value = refreshed;
    }
  } catch {
    if (current === sequence) error.value = true;
  } finally {
    if (current === sequence) busy.value = false;
  }
}

function search() {
  filters.page = 1;
  load({ preservePage: false });
}

function reset() {
  filters.keyword = "";
  filters.categoryId = null;
  filters.status = "";
  search();
}

async function fetchDetail(id) { return (await http.get(`/api/admin/items/${id}`)).data; }
async function loadAudits() {
  const id = detail.value?.id, current = ++detailSequence;
  if (!id) return;
  detailError.value = "";
  try {
    const { data } = await http.get(`/api/admin/items/${id}/review-audits`, { params: {page:auditPage.value,size:5} });
    if (current !== detailSequence) return;
    audits.value = data.records; auditTotal.value = data.total;
  } catch { if (current === detailSequence) detailError.value = "审核记录读取失败，请重试"; }
}
async function show(item) {
  const id=item.id, current=++openSequence;
  detailSequence++;
  detail.value = null; audits.value = []; drawer.value = true; detailError.value = ""; auditPage.value = 1;
  try { const loaded = await fetchDetail(id); if (current !== openSequence) return; detail.value = loaded; await loadAudits(); }
  catch { if (current === openSequence) detailError.value = "物品详情读取失败，请关闭后重试"; }
}
async function openReview(item, decision) {
  const current=++reviewSequence;
  if (showFixture) reviewTarget.value = item;
  else {
    try { const loaded=await fetchDetail(item.id); if (current !== reviewSequence) return; reviewTarget.value = loaded; }
    catch { if (current === reviewSequence) error.value = true; return; }
  }
  reviewDecision.value = decision;
  reviewVisible.value = true;
}
async function submitReview(payload, item) { return (await http.post(`/api/admin/items/${item.id}/review`, payload)).data; }
async function reviewSaved(event) {
  if (showFixture) return fixtureSaved(event);
  reviewVisible.value = false;
  await load();
  if (drawer.value && detail.value) await show(detail.value);
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
  fixtureResult.value = `已捕获请求体：${JSON.stringify(payload)}；未调用 API，未改变物品状态。`;
  reviewVisible.value = false;
  pendingFixture.value = null;
}

function finishPendingFixture() {
  pendingFixture.value?.();
  pendingFixture.value = null;
}

async function handleConflict(id) {
  conflictRefreshes.value += 1;
  await load({ preservePage: true });
  if (drawer.value && detail.value?.id === id) await show(detail.value);
  // The dialog retains its old version and reason until explicit content refresh.
}

function openFixture(decision) {
  openReview(
    {
      id: 9001,
      title: "审核组件夹具物品",
      status: "PENDING_REVIEW",
      version: 3,
    },
    decision,
  );
}

const statusLabel = (value) =>
  ({
    DRAFT: "草稿",
    PENDING_REVIEW: "待审核",
    AVAILABLE: "可交换",
    REJECTED: "已驳回",
    RESERVED: "交换中",
    EXCHANGED: "已交换",
    HIDDEN: "已隐藏",
  })[value] || value;
const statusType = (value) =>
  ({ PENDING_REVIEW: "warning", REJECTED: "danger", AVAILABLE: "success", HIDDEN: "danger" })[
    value
  ] || "info";

onMounted(async () => {
  const requestedStatus = String(route.query.status || "");
  if (["PENDING_REVIEW", "REJECTED", "AVAILABLE", "RESERVED", "EXCHANGED", "DRAFT", "HIDDEN"].includes(requestedStatus))
    filters.status = requestedStatus;
  await load();
  try {
    categories.value = (await http.get("/api/categories", { params: { includeInactive: true } })).data;
  } catch {}
  if (/^[1-9][0-9]*$/.test(String(route.query.id || ""))) await show({id:route.query.id});
});
const formatDate=value=>value?new Date(/(?:Z|[+-]\d{2}:\d{2})$/.test(value)?value:value+'Z').toLocaleString('zh-CN',{hour12:false}):'—';
</script>

<template>
  <el-button @click="manageSpotlights">管理首页曝光</el-button><el-dialog v-model="spotlightsOpen" title="首页曝光物品" width="min(640px,94vw)"><el-table :data="spotlights"><el-table-column prop="title" label="物品"/><el-table-column prop="sortOrder" label="顺序" width="80"/><el-table-column prop="status" label="状态" width="120"/><el-table-column label="操作" width="90"><template #default="{row}"><el-button type="danger" :disabled="spotlightBusy" @click="removeSpotlight(row)">移除</el-button></template></el-table-column></el-table><p>只有当前可交换物品会在首页展示；已下架或已交换物品自动停止曝光。</p></el-dialog>
  <div class="page-heading review-heading">
    <div>
      <span class="eyebrow">CONTENT REVIEW</span>
      <h1>物品审核</h1>
      <p>核对物品描述与图片，处理同学提交的闲置。</p>
    </div>
    <span class="count-pill">本页待审 {{ pendingCount }} · 共 {{ total }} 件</span>
  </div>



  <section class="panel">
    <form class="filter-bar review-filter" @submit.prevent="search">
      <el-input
        v-model="filters.keyword"
        aria-label="搜索物品"
        clearable
        placeholder="搜索物品名称"
        :prefix-icon="Search"
      />
      <el-select
        v-model="filters.categoryId"
        aria-label="物品分类"
        clearable
        placeholder="全部分类"
      >
        <el-option
          v-for="cat in categories"
          :key="cat.id"
          :value="cat.id"
          :label="cat.name"
        />
      </el-select>
      <el-select v-model="filters.status" aria-label="物品状态" clearable placeholder="全部状态">
        <el-option v-for="state in ['PENDING_REVIEW','REJECTED','AVAILABLE','RESERVED','EXCHANGED','DRAFT','HIDDEN']" :key="state" :value="state" :label="statusLabel(state)" />
      </el-select>
      <el-button type="primary" native-type="submit" :loading="busy"
        >搜索</el-button
      >
      <el-button :icon="RefreshRight" @click="reset">重置</el-button>
    </form>
    <el-alert
      v-if="error"
      title="物品读取失败，当前列表未被替换。"
      type="error"
      :closable="false"
      show-icon
    />
    <el-table :data="items" v-loading="busy" row-key="id" style="width: 100%">
      <el-table-column label="物品信息" min-width="275">
        <template #default="{ row }">
          <div class="table-item">
            <img
              :src="imageUrl(row.imageUrl) || '/demo/book.svg'"
              :alt="row.title"
            />
            <div>
              <strong>{{ row.title }}</strong>
              <small>{{ row.categoryName }} · 成色 {{ row.conditionLevel }}</small>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="ownerName" label="发布者" min-width="110" />
      <el-table-column label="交换需求" min-width="160">
        <template #default="{ row }">
          <span class="want-label">{{ row.wantedCategoryName || "未设置" }}</span>
          <small class="table-sub">{{
            (row.wantedTags || []).join(" · ") || "分类匹配"
          }}</small>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="155">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" effect="light" round>{{
            statusLabel(row.status)
          }}</el-tag><small class="table-sub" v-if="row.reviewBasis === 'LEGACY_DIRECT'">历史直发</small>
        </template>
      </el-table-column>
      <el-table-column label="发布时间" min-width="165">
        <template #default="{ row }">{{
          formatDate(row.createdAt)
        }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="show(row)">详情</el-button><el-button v-if="row.status==='AVAILABLE'" type="primary" link @click="expose(row)">首页曝光</el-button>
          <template v-if="row.status === 'PENDING_REVIEW'">
            <el-button type="success" link @click="openReview(row, 'APPROVE')">通过</el-button>
            <el-button type="danger" link @click="openReview(row, 'REJECT')">驳回</el-button>
          </template>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '读取失败，请重试' : '没有找到物品，试试其他关键词'"
          :image-size="72"
        />
      </template>
    </el-table>
    <div class="pagination-row">
      <span></span>
      <el-pagination
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

  <section v-if="showFixture" class="panel review-fixture">
    <div class="section-heading fixture-heading">
      <div>
        <span class="eyebrow">LOCAL COMPONENT FIXTURE</span>
        <h2>审核决定组件夹具</h2>
        <p>仅验证表单与错误恢复，不调用审核 API，不修改当前列表。</p>
      </div>
      <el-tag type="warning" round>非正式入口</el-tag>
    </div>
    <div class="fixture-controls">
      <el-select v-model="fixtureOutcome" aria-label="审核夹具响应场景">
        <el-option label="捕获请求体（不写入）" value="preview" />
        <el-option label="409 冲突并回读" value="conflict" />
        <el-option label="403 权限不足" value="forbidden" />
        <el-option label="请求失败" value="failure" />
        <el-option label="保持处理中（验证防重入）" value="pending" />
      </el-select>
      <el-button type="success" :icon="Check" @click="openFixture('APPROVE')"
        >通过夹具</el-button
      >
      <el-button type="danger" :icon="Close" @click="openFixture('REJECT')"
        >驳回夹具</el-button
      >
    </div>
    <el-alert
      v-if="fixtureResult"
      :title="fixtureResult"
      type="info"
      :closable="false"
    />
    <p v-if="conflictRefreshes" class="fixture-status">
      409 已触发管理列表回读 {{ conflictRefreshes }} 次；未覆盖服务端记录。
    </p>
  </section>

  <el-drawer
    v-model="drawer"
    :lock-scroll="false"
    title="物品与审核详情"
    size="min(560px, 100vw)"
    destroy-on-close
  >
    <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
    <template v-if="detail"><AssistantReview :key="detail.id" :endpoint="`/api/admin/items/${detail.id}/assist-review`"/>
      <div class="detail-surface">
        <div class="review-gallery">
          <el-image v-for="(photo,index) in (detail.imageUrls?.length ? detail.imageUrls : [detail.imageUrl])" :key="photo || index"
            class="detail-image" :src="imageUrl(photo) || '/demo/book.svg'" :alt="`${detail.title} · 图片 ${index+1}`" fit="contain"
            :preview-src-list="(detail.imageUrls?.length ? detail.imageUrls : [detail.imageUrl]).map(p=>imageUrl(p) || '/demo/book.svg')" :initial-index="index" preview-teleported />
        </div>
        <div class="detail-tags">
          <el-tag round>{{ detail.categoryName }}</el-tag>
          <el-tag :type="statusType(detail.status)" round>{{
            statusLabel(detail.status)
          }}</el-tag>
        </div>
        <h2>{{ detail.title }}</h2>
        <p class="detail-description">{{ detail.description }}</p>
        <dl class="detail-data">
          <div><dt>发布者</dt><dd>{{ detail.ownerName }}</dd></div>
          <div><dt>成色</dt><dd>{{ detail.conditionLevel }}</dd></div>
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
          <div><dt>记录编号</dt><dd>#{{ detail.id }}</dd></div>

          <div><dt>审核人</dt><dd>{{ detail.reviewedByName || "暂无审核决定" }}</dd></div>
          <div><dt>审核时间</dt><dd>{{ detail.reviewedAt || "—" }}</dd></div>
          <div class="detail-wide">
            <dt>最近决定（版本 {{ detail.reviewedVersion ?? "—" }}）</dt><dd>{{ detail.reviewReason || "暂无审核决定" }}</dd>
          </div>
        </dl>
      </div>
      <div class="drawer-review-actions">
        <el-button
          type="success"
          :icon="Check"
          :disabled="detail.status !== 'PENDING_REVIEW'"
          @click="openReview(detail, 'APPROVE')"
          >通过审核</el-button
        >
        <el-button
          type="danger"
          :icon="Close"
          :disabled="detail.status !== 'PENDING_REVIEW'"
          @click="openReview(detail, 'REJECT')"
          >驳回物品</el-button
        >
      </div>
      <h3>状态与内容审计</h3>
      <p v-if="!audits.length">暂无审核记录。</p>
      <div v-for="audit in audits" :key="audit.id" class="audit-event">
        <strong>{{ {SUBMIT:'提交审核',APPROVE:'通过',REJECT:'驳回',WITHDRAW:'下架'}[audit.action] }} · {{ audit.operatorDisplayName }}</strong>
        <p>{{ audit.previousStatus || '新物品' }} → {{ audit.newStatus }} · v{{ audit.previousVersion ?? '—' }} → v{{ audit.newVersion }} · {{ audit.createdAt }}</p>
        <p>{{ audit.reason }}</p>
        <details><summary>查看当时内容</summary><pre>{{ JSON.stringify({before:audit.previousSnapshot,after:audit.newSnapshot}, null, 2) }}</pre></details>
      </div>
      <el-button v-if="detailError" @click="loadAudits">重试审核记录</el-button>
      <el-pagination v-model:current-page="auditPage" :page-size="5" :total="auditTotal" layout="prev, pager, next" @current-change="loadAudits" />
    </template>
  </el-drawer>

  <ReviewDecisionDialog
    :visible="reviewVisible"
    :item="reviewTarget"
    :decision="reviewDecision"
    :submit-request="showFixture ? fixtureSubmit : submitReview"
    :refresh-request="showFixture ? null : fetchDetail"
    @refreshed="reviewTarget = $event"
    :fixture="showFixture"
    @close="reviewVisible = false"
    @saved="reviewSaved"
    @conflict="handleConflict"
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
  </ReviewDecisionDialog>
</template>

<style scoped>
.audit-event {padding:16px 0;border-bottom:1px solid var(--cl-border);overflow-wrap:anywhere;}
.audit-event pre {white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;}
.review-filter > .el-select {width:170px;}
.review-contract-alert {
  margin-bottom: 20px;
}
.disabled-action-wrap {
  display: inline-flex;
}
.review-filter .disabled-action-wrap .el-select {
  width: 190px;
}
.fixture-heading {
  align-items: flex-start;
}
.fixture-controls,
.drawer-review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.fixture-controls .el-select {
  width: min(100%, 260px);
}
.fixture-status {
  margin: 14px 0 0;
  color: var(--cl-muted);
  font-size: 12px;
}
.fixture-complete {
  margin-top: 12px;
}
.detail-surface {
  padding: 2px;
  color: var(--cl-text);
  background: var(--cl-surface);
}
.detail-description {
  overflow-wrap: anywhere;
}
.detail-wide {
  grid-column: 1 / -1;
}
.drawer-review-actions {
  margin: 22px 0 16px;
}
@media (max-width: 900px) {
  .review-filter .disabled-action-wrap {
    flex: 1;
  }
  .review-filter .disabled-action-wrap .el-select {
    width: 100%;
  }
}
@media (max-width: 640px) {
  .review-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .fixture-controls > * {
    width: 100%;
  }
}
.review-gallery{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.review-gallery .detail-image{width:100%;height:220px;object-fit:contain}.review-gallery .detail-image:only-child{grid-column:1 / -1}
</style>
