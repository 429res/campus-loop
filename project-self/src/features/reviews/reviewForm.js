export function normalizeReviewReason(value) {
  return String(value ?? "").trim();
}

export function validateReviewReason(value) {
  const reason = normalizeReviewReason(value);
  return !reason ? "请输入审核理由" : reason.length > 1000 ? "审核理由最多1000字" : "";
}

export function reviewMutationError(error) {
  const status = error?.response?.status ?? error?.status;
  if (status === 403) return "当前账号没有物品审核权限";
  if (status === 409)
    return "物品状态、版本或占用已变化，理由已保留，请重新读取并核对";
  return "审核请求失败，理由已保留，请稍后重试";
}
