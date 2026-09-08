import test from "node:test";
import assert from "node:assert/strict";
import {
  buildReportPayload,
  normalizeReportReason,
  REPORT_ACTIONS,
  reportMutationError,
  validateReportReason,
} from "../src/features/reports/reportForm.js";

test("report reason is trimmed and validated", () => {
  assert.equal(normalizeReportReason("  核对证据  "), "核对证据");
  assert.equal(validateReportReason("  "), "请输入处理理由");
  assert.equal(validateReportReason("a".repeat(1001)), "处理理由最多1000字");
});

test("accept payload cannot smuggle a decision", () => {
  assert.deepEqual(buildReportPayload({ action: REPORT_ACTIONS.ACCEPT, version: 2, decision: "UPHELD", reason: "  开始核对 " }), { version: 2, reason: "开始核对" });
});

test("decision payload uses the stale version supplied by the opened record", () => {
  assert.deepEqual(buildReportPayload({ action: REPORT_ACTIONS.DECIDE, version: 4, decision: "DISMISSED", reason: "证据不足" }), { version: 4, reason: "证据不足", decision: "DISMISSED" });
});

test("409 and 403 errors explain preservation and authorization", () => {
  assert.match(reportMutationError({ status: 409 }), /理由已保留/);
  assert.match(reportMutationError({ response: { status: 403 } }), /权限/);
});
