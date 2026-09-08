import test from "node:test";
import assert from "node:assert/strict";
import {
  CATEGORY_NAME_MAX_LENGTH,
  categoryMutationError,
  normalizeCategoryName,
  validateCategoryName,
} from "../src/features/categories/categoryForm.js";

test("分类名称提交前去除首尾空格", () => {
  assert.equal(normalizeCategoryName("  图书教材  "), "图书教材");
});

test("分类名称拒绝空白和超长值", () => {
  assert.equal(validateCategoryName(" \n "), "请输入分类名称");
  assert.equal(
    validateCategoryName("分".repeat(CATEGORY_NAME_MAX_LENGTH + 1)),
    "分类名称不能超过 64 个字符",
  );
  assert.equal(validateCategoryName("数码电子"), "");
});

test("分类写入错误保留可恢复语义", () => {
  assert.match(categoryMutationError({ status: 403 }), /权限/);
  assert.match(categoryMutationError({ response: { status: 409 } }), /刷新/);
  assert.match(categoryMutationError({ status: 503 }), /输入已保留/);
});
