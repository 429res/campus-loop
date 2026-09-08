import test from "node:test";
import assert from "node:assert/strict";
import {
  buildUserStatusPayload,
  normalizeUserStatusReason,
  userStatusMutationError,
  validateUserStatusReason,
} from "../src/features/users/userStatusForm.js";

test("账号启停理由提交前去除首尾空格", () => {
  assert.equal(normalizeUserStatusReason("  账号本人申请暂停  "), "账号本人申请暂停");
});

test("账号启停请求携带目标状态、最新版本与规范化理由", () => {
  assert.deepEqual(
    buildUserStatusPayload({ status: "ACTIVE", version: 7 }, "  分阶段暂停使用  "),
    { status: "DISABLED", version: 7, reason: "分阶段暂停使用" },
  );
  assert.deepEqual(
    buildUserStatusPayload({ status: "DISABLED", version: 8 }, "恢复使用"),
    { status: "ACTIVE", version: 8, reason: "恢复使用" },
  );
  assert.throws(() => buildUserStatusPayload({ status: "ACTIVE" }, "理由"), /并发版本/);
});

test("账号启停理由拒绝空白和超长值", () => {
  assert.equal(validateUserStatusReason(" \n "), "请输入启停理由");
  assert.match(validateUserStatusReason("字".repeat(501)), /500/);
  assert.equal(validateUserStatusReason("恢复校园账号使用"), "");
});

test("账号启停错误保留权限、冲突与恢复语义", () => {
  assert.match(userStatusMutationError({ status: 403 }), /权限/);
  assert.match(userStatusMutationError({ response: { status: 409 } }), /重新读取/);
  assert.match(userStatusMutationError({ status: 503 }), /理由已保留/);
});
