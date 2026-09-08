export const REPORT_ACTIONS = Object.freeze({
  ACCEPT: "ACCEPT",
  DECIDE: "DECIDE",
});

export const REPORT_DECISIONS = Object.freeze(["UPHELD", "DISMISSED"]);

export function normalizeReportReason(value) {
  return String(value ?? "").trim();
}

export function validateReportReason(value) {
  const reason = normalizeReportReason(value);
  return !reason
    ? "请输入处理理由"
    : reason.length > 1000
      ? "处理理由最多1000字"
      : "";
}

export function reportMutationError(error) {
  const status = error?.response?.status ?? error?.status;
  if (status === 403) return "当前账号没有举报处理或证据访问权限";
  if (status === 404) return "举报或目标已不存在，理由已保留";
  if (status === 409)
    return "举报状态或版本已变化，理由已保留，请重新读取并核对";
  return "请求失败，理由已保留，请稍后重试";
}

export function buildReportPayload({ action, version, decision, reason }) {
  const payload = { version, reason: normalizeReportReason(reason) };
  if (action === REPORT_ACTIONS.DECIDE) payload.decision = decision;
  return payload;
}
