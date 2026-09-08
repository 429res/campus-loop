export function buildParticipantRows(exchange) {
  const participants = exchange?.participants || [];
  const flows = exchange?.flows || [];
  return participants.map((participant) => {
    const outgoing = flows.find((flow) => flow.fromUserId === participant.userId);
    const incoming = flows.find((flow) => flow.toUserId === participant.userId);
    return {
      ...participant,
      offeredItemId: outgoing?.itemId ?? participant.offeredItemId,
      receivedItemId: incoming?.itemId ?? participant.receivedItemId,
    };
  });
}

export function disputeImpactFacts(exchange) {
  if (exchange?.status !== "DISPUTED") return [];
  return [
    "物品所有权保持争议登记前状态，不推断实物已退回",
    "相关物品继续保持 RESERVED",
    "本交换占用继续保留",
    "精确引用需求继续保持 ACTIVE",
    "新的确认、交接、取消和自动到期均停止",
  ];
}

export function disputeLoadError(error) {
  const status = error?.response?.status ?? error?.status;
  if (status === 403) return "当前账号没有管理员争议读取权限";
  if (status === 404) return "争议或关联交换不存在";
  if (status === 409) return "交换事实已变化，请重新读取后判断";
  return "争议队列读取失败，请重试";
}
