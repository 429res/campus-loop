export function createLatestRequestGuard(readIdentity = () => undefined) {
  let generation = 0
  return {
    begin() { return { generation: ++generation, identity: readIdentity() } },
    isLatest(ticket) { return ticket.generation === generation },
    isCurrent(ticket) { return ticket.generation === generation && ticket.identity === readIdentity() },
    invalidate() { generation++ },
  }
}
