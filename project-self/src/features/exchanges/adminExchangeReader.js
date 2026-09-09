import { reactive } from "vue";

export const exchangeStatuses = [
  { value: "AWAITING_CONFIRMATION", label: "等待确认", type: "warning" },
  { value: "READY", label: "待交接", type: "primary" },
  { value: "COMPLETED", label: "已完成", type: "success" },
  { value: "CANCELLED", label: "已取消", type: "info" },
  { value: "EXPIRED", label: "已到期", type: "info" },
  { value: "DISPUTED", label: "有争议", type: "danger" },
];
export const statusLabel = value => exchangeStatuses.find(status => status.value === value)?.label || value || "未记录";
export const statusType = value => exchangeStatuses.find(status => status.value === value)?.type || "info";
export const eventLabel = value => ({ CONFIRMED: "确认邀请", CANCELLED: "取消交换", EXPIRED: "自动到期", HANDED_OFF: "声明已交出", RECEIVED: "声明已收到", DISPUTED: "登记争议", DISPUTE_RESUMED: "恢复交接", ADMIN_CANCELLED: "终止交换" })[value] || value;
export function formatExchangeTime(value) {
  if (!value) return "未记录";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "时间不可用" : new Intl.DateTimeFormat("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
  }).format(date);
}

export function exchangeReadError(error) {
  const status = error?.response?.status || error?.status;
  if (status === 401) return "登录已过期，请重新登录管理员账号。";
  if (status === 403) return "当前账号没有查看管理交换记录的权限。";
  if (status === 404) return "这条交换记录不存在或已不可读取。";
  if (status === 409) return "交换记录存在不完整数据，暂时无法读取。请重新读取；若仍失败，请联系后台维护人员。";
  return "交换记录读取失败，请检查连接后重试。";
}

// Each channel owns its request and generation. Session checks also cover callbacks
// that settle after an abort, before a page watcher has observed an account change.
export function createAdminExchangeReader({ get, readSession }) {
  const state = reactive({
    records: [], total: 0, page: 1, size: 12, status: "", busy: false, error: "",
    drawer: false, detailId: null, detail: null, detailBusy: false, detailError: "",
  });
  let listGeneration = 0, detailGeneration = 0, listRequest, detailRequest, disposed = false;

  async function loadList(page = state.page) {
    if (disposed) return;
    const generation = ++listGeneration, session = readSession();
    listRequest?.abort();
    listRequest = new AbortController();
    const current = () => !disposed && generation === listGeneration && session === readSession();
    state.page = page;
    state.records = [];
    state.error = "";
    state.busy = false;
    if (!session) { state.total = 0; return; }
    state.busy = true;
    const params = { page, size: state.size, ...(state.status ? { status: state.status } : {}) };
    try {
      const { data } = await get("/api/admin/exchanges", { params, signal: listRequest.signal, silent: true });
      if (!current()) return;
      const lastPage = Math.max(1, Math.ceil(data.total / state.size));
      if (page > lastPage) return await loadList(lastPage);
      state.records = data.records;
      state.total = data.total;
    } catch (error) {
      if (current() && error?.code !== "ERR_CANCELED") { state.error = exchangeReadError(error); state.total = 0; }
    } finally {
      if (current()) state.busy = false;
    }
  }

  async function openDetail(id = state.detailId) {
    if (disposed || !id) return;
    const generation = ++detailGeneration, session = readSession();
    detailRequest?.abort();
    detailRequest = new AbortController();
    const current = () => !disposed && generation === detailGeneration && session === readSession();
    state.detailId = id;
    state.detail = null;
    state.detailError = "";
    state.detailBusy = false;
    state.drawer = true;
    if (!session) { closeDetail(); return; }
    state.detailBusy = true;
    try {
      const { data } = await get(`/api/admin/exchanges/${id}`, { signal: detailRequest.signal, silent: true });
      if (!current()) return;
      state.detail = data;
    } catch (error) {
      if (current() && error?.code !== "ERR_CANCELED") state.detailError = exchangeReadError(error);
    } finally {
      if (current()) state.detailBusy = false;
    }
  }

  function closeDetail() {
    detailGeneration++;
    detailRequest?.abort();
    state.drawer = false;
    state.detailId = null;
    state.detail = null;
    state.detailBusy = false;
    state.detailError = "";
  }
  function reset() {
    listGeneration++;
    listRequest?.abort();
    state.records = [];
    state.total = 0;
    state.busy = false;
    state.error = "";
    state.page = 1;
    state.status = "";
    closeDetail();
  }
  function dispose() { disposed = true; reset(); }
  return { state, loadList, openDetail, closeDetail, reset, dispose };
}
