export const USER_STATUS_REASON_MAX_LENGTH = 500;

export function normalizeUserStatusReason(value) {
  return String(value ?? "").trim();
}

export function validateUserStatusReason(value) {
  const reason = normalizeUserStatusReason(value);
  if (!reason) return "请输入启停理由";
  if (reason.length > USER_STATUS_REASON_MAX_LENGTH)
    return `理由不能超过 ${USER_STATUS_REASON_MAX_LENGTH} 个字符`;
  return "";
}

export function buildUserStatusPayload(user, reason) {
  if (!Number.isInteger(user?.version) || user.version < 0)
    throw new TypeError("当前账号缺少并发版本，请刷新后重试");
  return {
    status: user.status === "ACTIVE" ? "DISABLED" : "ACTIVE",
    version: user.version,
    reason: normalizeUserStatusReason(reason),
  };
}

export function userStatusMutationError(error) {
  const status = error?.response?.status ?? error?.status;
  const serverMessage = error?.response?.data?.msg;
  if (status === 401) return "当前会话已失效，请重新登录";
  if (status === 403) return "当前账号没有管理账号状态的权限";
  if (status === 409)
    return serverMessage || "账号状态或版本已变化，已重新读取，请再次判断";
  if (status === 404) return "目标账号不存在，已重新读取列表";
  if (status === 400) return serverMessage || "提交内容不符合账号启停契约";
  return "账号状态更新失败，理由已保留，请稍后重试";
}
