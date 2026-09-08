<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { RefreshRight, Search, UserFilled } from "@element-plus/icons-vue";
import UserStatusDialog from "@/components/users/UserStatusDialog.vue";
import { useOverlayLock } from "@/composables/useOverlayLock";
import { useAuth } from "@/stores/auth";
import http from "@/http";

const auth = useAuth();
const filters = reactive({ keyword: "", role: null, status: null, page: 1, size: 12 });
const users = ref([]);
const total = ref(0);
const loading = ref(false);
const readError = ref("");
const detail = ref(null);
const drawerVisible = ref(false);
const statusVisible = ref(false);
const statusTarget = ref(null);
const resultNotice = ref("");
const audits = ref([]);
const auditTotal = ref(0);
const auditPage = ref(1);
const auditLoading = ref(false);
const auditError = ref("");
let listSequence = 0;
let auditSequence = 0;

const activeCount = computed(
  () => users.value.filter((user) => user.status === "ACTIVE").length,
);
useOverlayLock(drawerVisible);

function requestStatus(error) {
  return error?.response?.status ?? error?.status;
}

async function loadUsers({ preservePage = true } = {}) {
  const current = ++listSequence;
  loading.value = true;
  readError.value = "";
  try {
    const { data } = await http.get("/api/admin/users", { params: filters });
    if (current !== listSequence) return;
    users.value = data.records;
    total.value = data.total;
    if (!preservePage) filters.page = 1;
    if (detail.value) {
      const refreshed = data.records.find((user) => user.id === detail.value.id);
      if (refreshed) detail.value = refreshed;
    }
  } catch (error) {
    if (current !== listSequence) return;
    readError.value =
      requestStatus(error) === 403
        ? "当前账号没有账号查询权限。"
        : "账号读取失败，当前列表未被替换。";
  } finally {
    if (current === listSequence) loading.value = false;
  }
}

function search() {
  filters.page = 1;
  loadUsers({ preservePage: false });
}

function reset() {
  filters.keyword = "";
  filters.role = null;
  filters.status = null;
  search();
}

async function loadAudits(userId, page = auditPage.value) {
  const current = ++auditSequence;
  auditLoading.value = true;
  auditError.value = "";
  try {
    const { data } = await http.get(`/api/admin/users/${userId}/status-audits`, {
      params: { page, size: 20 },
    });
    if (current !== auditSequence) return;
    audits.value = data.records;
    auditTotal.value = data.total;
    auditPage.value = data.page;
  } catch (error) {
    if (current !== auditSequence) return;
    auditError.value =
      requestStatus(error) === 403
        ? "当前账号没有审计查询权限。"
        : "启停记录读取失败，请稍后重试。";
  } finally {
    if (current === auditSequence) auditLoading.value = false;
  }
}

function showDetails(user) {
  detail.value = user;
  drawerVisible.value = true;
  auditPage.value = 1;
  audits.value = [];
  loadAudits(user.id, 1);
}

function openStatus(user) {
  statusTarget.value = user;
  resultNotice.value = "";
  statusVisible.value = true;
}

async function submitStatus(payload, user) {
  const { data } = await http.patch(`/api/admin/users/${user.id}/status`, payload);
  return data;
}

async function handleSaved({ result }) {
  statusVisible.value = false;
  resultNotice.value = `账号 ${result.username} 已由服务端确认为${statusLabel(result.status)}。`;
  await loadUsers({ preservePage: true });
  const refreshed = users.value.find((user) => user.id === result.id);
  detail.value = refreshed || result;
  statusTarget.value = detail.value;
  if (drawerVisible.value) await loadAudits(result.id, 1);
}

async function refreshConflict(id) {
  await loadUsers({ preservePage: true });
  const refreshed = users.value.find((user) => user.id === id);
  if (refreshed) {
    statusTarget.value = refreshed;
    if (detail.value?.id === id) detail.value = refreshed;
  }
  if (drawerVisible.value && detail.value?.id === id) await loadAudits(id, 1);
}

async function handleMissing() {
  await loadUsers({ preservePage: true });
}

function changeAuditPage(page) {
  if (detail.value) loadAudits(detail.value.id, page);
}

const statusLabel = (status) =>
  ({ ACTIVE: "启用", DISABLED: "停用" })[status] || status;
const roleLabel = (role) => ({ ADMIN: "管理员", USER: "普通用户" })[role] || role;
function formatTime(value) {
  if (!value) return "—";
  const source = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : `${value}Z`;
  const date = new Date(source);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString("zh-CN", { hour12: false });
}

onMounted(() => loadUsers());
</script>

<template>
  <div class="page-heading users-heading">
    <div>
      <span class="eyebrow">ACCOUNT DIRECTORY</span>
      <h1>账号查询与启停</h1>
      <p>查询安全公开字段；权限、并发和管理员保护始终由服务端裁定。</p>
    </div>
    <span class="count-pill">本页启用 {{ activeCount }} · 共 {{ total }} 个账号</span>
  </div>

  <el-alert
    v-if="resultNotice"
    class="users-notice"
    :title="resultNotice"
    type="success"
    :closable="false"
    show-icon
  />

  <section class="panel">
    <form class="filter-bar users-filter" @submit.prevent="search">
      <el-input
        v-model="filters.keyword"
        aria-label="搜索账号"
        clearable
        :prefix-icon="Search"
        placeholder="用户名或显示名称"
      />
      <el-select v-model="filters.role" aria-label="筛选角色" clearable placeholder="全部角色">
        <el-option label="管理员" value="ADMIN" />
        <el-option label="普通用户" value="USER" />
      </el-select>
      <el-select v-model="filters.status" aria-label="筛选状态" clearable placeholder="全部状态">
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="DISABLED" />
      </el-select>
      <el-button type="primary" native-type="submit" :loading="loading">搜索</el-button>
      <el-button :icon="RefreshRight" @click="reset">重置</el-button>
    </form>

    <el-alert
      v-if="readError"
      :title="readError"
      type="error"
      :closable="false"
      show-icon
    />

    <el-table v-loading="loading" :data="users" row-key="id" style="width: 100%">
      <el-table-column label="账号" min-width="235">
        <template #default="{ row }">
          <div class="account-cell">
            <span class="account-avatar" aria-hidden="true">{{ (row.displayName || row.username).slice(0, 1) }}</span>
            <div>
              <strong>{{ row.displayName }}</strong>
              <small>@{{ row.username }} · #{{ row.id }}</small>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="角色" min-width="110">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'warning' : 'info'" effect="light" round>{{ roleLabel(row.role) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'danger'" effect="light" round>{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" min-width="165">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="showDetails(row)">详情</el-button>
          <el-button
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            link
            @click="openStatus(row)"
          >{{ row.status === "ACTIVE" ? "停用" : "启用" }}</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="readError ? '读取失败，请重试' : '没有匹配的账号'" :image-size="72" />
      </template>
    </el-table>

    <div class="pagination-row">
      <span>仅展示接口允许的账号安全字段</span>
      <el-pagination
        v-model:current-page="filters.page"
        :page-size="filters.size"
        :total="total"
        :pager-count="5"
        layout="prev, pager, next"
        background
        @current-change="loadUsers"
      />
    </div>
  </section>

  <el-drawer
    v-model="drawerVisible"
    :lock-scroll="false"
    title="账号公开资料与启停记录"
    size="min(620px, 100vw)"
    destroy-on-close
  >
    <template v-if="detail">
      <section class="profile-surface">
        <div class="profile-title">
          <span class="account-avatar large" aria-hidden="true">{{ (detail.displayName || detail.username).slice(0, 1) }}</span>
          <div>
            <h2>{{ detail.displayName }}</h2>
            <p>@{{ detail.username }}</p>
          </div>
          <el-tag v-if="detail.id === auth.user?.id" type="info" round>当前账号</el-tag>
        </div>
        <dl class="detail-data">
          <div><dt>账号编号</dt><dd>#{{ detail.id }}</dd></div>
          <div><dt>角色</dt><dd>{{ roleLabel(detail.role) }}</dd></div>
          <div><dt>状态</dt><dd>{{ statusLabel(detail.status) }}</dd></div>
          <div><dt>并发版本</dt><dd>{{ detail.version }}</dd></div>
          <div class="detail-wide"><dt>创建时间</dt><dd>{{ formatTime(detail.createdAt) }}</dd></div>
        </dl>
      </section>

      <div class="drawer-actions">
        <el-button
          :type="detail.status === 'ACTIVE' ? 'danger' : 'success'"
          :icon="UserFilled"
          @click="openStatus(detail)"
        >{{ detail.status === "ACTIVE" ? "停用此账号" : "恢复启用" }}</el-button>
      </div>
      <el-alert
        title="当前账号自停用、最后可用管理员及并发版本保护均由服务端验证。"
        type="info"
        :closable="false"
        show-icon
      />

      <div class="audit-heading">
        <h3>启停记录</h3>
        <el-button :icon="RefreshRight" text :loading="auditLoading" @click="loadAudits(detail.id, auditPage)">刷新</el-button>
      </div>
      <el-alert v-if="auditError" :title="auditError" type="error" :closable="false" show-icon />
      <el-timeline v-loading="auditLoading" class="audit-timeline">
        <el-timeline-item
          v-for="audit in audits"
          :key="audit.id"
          :timestamp="formatTime(audit.createdAt)"
          placement="top"
        >
          <div class="audit-card">
            <strong>{{ statusLabel(audit.previousStatus) }} → {{ statusLabel(audit.newStatus) }}</strong>
            <p>{{ audit.reason }}</p>
            <small>处理人：{{ audit.operatorDisplayName || audit.operatorUsername || `#${audit.operatorUserId}` }} · 版本 {{ audit.previousVersion }} → {{ audit.newVersion }}</small>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!auditLoading && !auditError && !audits.length" description="暂无启停记录" :image-size="64" />
      <el-pagination
        v-if="auditTotal > 20"
        v-model:current-page="auditPage"
        :page-size="20"
        :total="auditTotal"
        layout="prev, pager, next"
        small
        @current-change="changeAuditPage"
      />
    </template>
  </el-drawer>

  <UserStatusDialog
    :visible="statusVisible"
    :user="statusTarget"
    :submit-request="submitStatus"
    @close="statusVisible = false"
    @saved="handleSaved"
    @conflict="refreshConflict"
    @missing="handleMissing"
  />
</template>

<style scoped>
.users-notice {
  margin-bottom: 20px;
}
.account-cell,
.profile-title,
.drawer-actions,
.audit-heading {
  display: flex;
  align-items: center;
}
.account-cell {
  gap: 11px;
}
.account-cell div {
  min-width: 0;
}
.account-cell strong,
.account-cell small {
  display: block;
  overflow-wrap: anywhere;
}
.account-cell small,
.profile-title p,
.audit-card small {
  color: var(--cl-muted);
  font-size: 12px;
}
.account-avatar {
  display: grid;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--cl-border);
  border-radius: 50%;
  background: var(--cl-surface-soft);
  color: var(--cl-primary);
  font-weight: 750;
}
.account-avatar.large {
  flex-basis: 48px;
  width: 48px;
  height: 48px;
  font-size: 18px;
}
.profile-surface,
.audit-card {
  border: 1px solid var(--cl-border);
  background: var(--cl-surface);
}
.profile-surface {
  padding: 18px;
  border-radius: 16px;
}
.profile-title {
  gap: 14px;
}
.profile-title div {
  min-width: 0;
  margin-right: auto;
}
.profile-title h2,
.profile-title p {
  margin: 0;
  overflow-wrap: anywhere;
}
.detail-wide {
  grid-column: 1 / -1;
}
.drawer-actions {
  gap: 12px;
  margin: 20px 0 14px;
}
.audit-heading {
  justify-content: space-between;
  margin-top: 28px;
}
.audit-heading h3 {
  margin: 0;
}
.audit-timeline {
  padding-top: 8px;
}
.audit-card {
  padding: 12px 14px;
  border-radius: 12px;
}
.audit-card p {
  margin: 7px 0;
  overflow-wrap: anywhere;
}
@media (max-width: 900px) {
  .users-filter .el-input,
  .users-filter .el-select {
    flex: 1 1 180px;
  }
}
@media (max-width: 640px) {
  .users-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .users-filter .el-input,
  .users-filter .el-select,
  .users-filter .el-button {
    width: 100%;
  }
}
</style>
