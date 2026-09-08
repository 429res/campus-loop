export const CATEGORY_NAME_MAX_LENGTH = 64;

export function normalizeCategoryName(value) {
  return String(value ?? "").trim();
}

export function validateCategoryName(value) {
  const name = normalizeCategoryName(value);
  if (!name) return "请输入分类名称";
  if (name.length > CATEGORY_NAME_MAX_LENGTH)
    return `分类名称不能超过 ${CATEGORY_NAME_MAX_LENGTH} 个字符`;
  return "";
}

export function categoryMutationError(error) {
  const status = error?.response?.status ?? error?.status;
  if (status === 403) return "当前账号没有分类维护权限";
  if (status === 409)
    return "记录已变化或名称重复，列表已刷新，请核对后重试";
  return "请求失败，输入已保留，请稍后重试";
}
