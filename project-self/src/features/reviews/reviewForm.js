export function normalizeReviewReason(value) {
  return String(value ?? "").trim();
}

export function validateReviewReason(value) {
  return normalizeReviewReason(value) ? "" : "请输入审核理由";
}

export function reviewMutationError(error) {
  const status = error?.response?.status ?? error?.status;
  if (status === 403) return "当前账号没有物品审核权限";
  if (status === 409)
    return "物品状态或版本已变化，已重新读取，请核对后再次决定";
  return "审核请求失败，理由已保留，请稍后重试";
}
