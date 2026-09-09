import test from "node:test";
import assert from "node:assert/strict";
import { createAdminExchangeReader, exchangeReadError } from "../src/features/exchanges/adminExchangeReader.js";

function harness() {
  let session = "admin-a";
  const requests = [];
  const reader = createAdminExchangeReader({
    readSession: () => session,
    get: (url, options) => new Promise((resolve, reject) => requests.push({ url, options, resolve, reject })),
  });
  return { ...reader, requests, setSession: value => { session = value; } };
}
const list = (records, total = records.length) => ({ data: { records, total, page: 1, size: 12 } });
const detail = id => ({ data: { exchange: { id, status: "READY", allowedActions: [] }, events: [], creation: null } });

test("管理列表仅传入约定分页参数，清除状态时不发送空串", async () => {
  const h = harness();
  const all = h.loadList();
  assert.equal(h.requests[0].url, "/api/admin/exchanges");
  assert.deepEqual(h.requests[0].options.params, { page: 1, size: 12 });
  h.requests[0].resolve(list([]));
  await all;
  h.state.status = "COMPLETED";
  const filtered = h.loadList(2);
  assert.deepEqual(h.requests[1].options.params, { page: 2, size: 12, status: "COMPLETED" });
  h.requests[1].resolve(list([{ id: 20 }], 13));
  await filtered;
  assert.equal(h.state.page, 2);
  assert.equal(h.state.total, 13);
});

test("快速切换状态和页码后，旧响应不能覆盖新列表", async () => {
  const h = harness();
  const old = h.loadList(2);
  h.state.status = "READY";
  const current = h.loadList(1);
  assert.equal(h.requests[0].options.signal.aborted, true);
  h.requests[1].resolve(list([{ id: 2, status: "READY" }]));
  await current;
  h.requests[0].resolve(list([{ id: 1, status: "COMPLETED" }], 30));
  await old;
  assert.deepEqual(h.state.records, [{ id: 2, status: "READY" }]);
  assert.equal(h.state.total, 1);
  assert.equal(h.state.busy, false);
});

test("刷新读取失败时移除旧列表，并保留409与空结果的区别", async () => {
  const h = harness();
  const first = h.loadList();
  h.requests[0].resolve(list([{ id: 1 }]));
  await first;
  const retry = h.loadList();
  assert.deepEqual(h.state.records, []);
  assert.equal(h.state.total, 1, "请求期间保留分页范围，避免分页控件自动跳回第一页");
  h.requests[1].reject({ response: { status: 409 } });
  await retry;
  assert.match(h.state.error, /不完整数据/);
  assert.equal(h.state.total, 0);
  assert.equal(h.state.busy, false);
  const empty = h.loadList();
  h.requests[2].resolve(list([]));
  await empty;
  assert.equal(h.state.error, "");
  assert.deepEqual(h.state.records, []);
});

test("记录总数缩小时，越界页回读最后有效页而非错配页码与内容", async () => {
  const h = harness();
  const loading = h.loadList(3);
  h.requests[0].resolve(list([], 13));
  await Promise.resolve();
  assert.deepEqual(h.requests[1].options.params, { page: 2, size: 12 });
  assert.equal(h.state.busy, true);
  h.requests[1].resolve(list([{ id: 13 }], 13));
  await loading;
  assert.equal(h.state.page, 2);
  assert.deepEqual(h.state.records, [{ id: 13 }]);
  assert.equal(h.state.busy, false);
});

test("切换详情立刻清除上一条记录，迟到详情不能替换当前记录", async () => {
  const h = harness();
  const first = h.openDetail(1);
  const second = h.openDetail(2);
  assert.equal(h.requests[0].options.signal.aborted, true);
  assert.equal(h.state.detail, null);
  h.requests[1].resolve(detail(2));
  await second;
  h.requests[0].resolve(detail(1));
  await first;
  assert.equal(h.state.detail.exchange.id, 2);
  assert.equal(h.state.detailId, 2);
  assert.equal(h.state.detailBusy, false);
});

test("关闭后再打开另一条记录，旧请求失败不能污染新抽屉", async () => {
  const h = harness();
  const first = h.openDetail(1);
  h.closeDetail();
  assert.equal(h.state.drawer, false);
  assert.equal(h.state.detailId, null);
  const second = h.openDetail(2);
  h.requests[0].reject({ response: { status: 409 } });
  await first;
  assert.equal(h.state.detailBusy, true);
  assert.equal(h.state.detailError, "");
  h.requests[1].resolve(detail(2));
  await second;
  assert.equal(h.state.detail.exchange.id, 2);
});

test("无历史事件或创建快照时保留服务端空值，不补造记录", async () => {
  const h = harness();
  const loading = h.openDetail(7);
  h.requests[0].resolve(detail(7));
  await loading;
  assert.deepEqual(h.state.detail, detail(7).data);
  assert.equal(h.state.detail.creation, null);
  assert.deepEqual(h.state.detail.events, []);
  assert.deepEqual(h.state.detail.exchange.allowedActions, []);
});

test("详情失败可重试同一ID，失败期间不显示旧快照", async () => {
  const h = harness();
  const first = h.openDetail(9);
  h.requests[0].resolve(detail(9));
  await first;
  const refresh = h.openDetail();
  assert.equal(h.state.detail, null);
  h.requests[1].reject({ response: { status: 404 } });
  await refresh;
  assert.match(h.state.detailError, /不存在/);
  const retry = h.openDetail();
  assert.equal(h.requests[2].url, "/api/admin/exchanges/9");
  h.requests[2].resolve(detail(9));
  await retry;
  assert.equal(h.state.detailError, "");
});

for (const channel of ["list", "detail"]) test(`${channel}在账号变更后，即使视图尚未重置也不接受旧响应`, async () => {
  const h = harness();
  const loading = channel === "list" ? h.loadList() : h.openDetail(1);
  h.setSession("admin-b");
  h.requests[0].resolve(channel === "list" ? list([{ id: 1 }]) : detail(1));
  await loading;
  assert.deepEqual(h.state.records, []);
  assert.equal(h.state.detail, null);
});

test("会话重置立即清除列表/详情且新会话不受旧请求影响", async () => {
  const h = harness();
  const oldList = h.loadList(), oldDetail = h.openDetail(1);
  h.setSession("admin-b");
  h.reset();
  assert.equal(h.state.drawer, false);
  assert.equal(h.state.detailId, null);
  assert.equal(h.state.detailBusy, false);
  assert.equal(h.requests.every(request => request.options.signal.aborted), true);
  const newList = h.loadList();
  h.requests[2].resolve(list([{ id: 2 }]));
  await newList;
  h.requests[0].reject({ response: { status: 401 } });
  h.requests[1].resolve(detail(1));
  await Promise.all([oldList, oldDetail]);
  assert.deepEqual(h.state.records, [{ id: 2 }]);
  assert.equal(h.state.detail, null);
  assert.equal(h.state.error, "");
});

test("卸载取消请求且不会重新打开已销毁的详情", async () => {
  const h = harness();
  const loading = h.openDetail(1);
  h.dispose();
  h.requests[0].resolve(detail(1));
  await loading;
  await h.openDetail(2);
  await h.loadList();
  assert.equal(h.state.drawer, false);
  assert.equal(h.state.detail, null);
  assert.equal(h.requests.length, 1);
});

test("无会话不发送管理读取请求", async () => {
  const h = harness();
  h.setSession("");
  await h.loadList();
  await h.openDetail(1);
  assert.equal(h.requests.length, 0);
  assert.equal(h.state.drawer, false);
});

test("权限/不存在/完整性错误分别提供读取恢复说明", () => {
  assert.match(exchangeReadError({ status: 401 }), /重新登录/);
  assert.match(exchangeReadError({ status: 403 }), /权限/);
  assert.match(exchangeReadError({ status: 404 }), /不存在/);
  assert.match(exchangeReadError({ status: 409 }), /不完整/);
  assert.match(exchangeReadError({ code: "ECONNABORTED" }), /重试/);
});
