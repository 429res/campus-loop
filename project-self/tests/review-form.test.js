import test from "node:test";
import assert from "node:assert/strict";
import {
  normalizeReviewReason,
  reviewMutationError,
  validateReviewReason,
} from "../src/features/reviews/reviewForm.js";

test("审核理由提交前去除首尾空格", () => {
  assert.equal(normalizeReviewReason("  内容与图片一致  "), "内容与图片一致");
});

test("审核理由拒绝空白内容", () => {
  assert.equal(validateReviewReason(" \n "), "请输入审核理由");
  assert.equal(validateReviewReason("内容真实"), "");
});

test("审核错误保留权限、冲突与恢复语义", () => {
  assert.match(reviewMutationError({ status: 403 }), /权限/);
  assert.match(reviewMutationError({ response: { status: 409 } }), /重新读取/);
  assert.match(reviewMutationError({ status: 503 }), /理由已保留/);
});
