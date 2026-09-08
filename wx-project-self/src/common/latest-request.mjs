export function createLatestRequestGuard(readIdentity = () => undefined) {
  let generation = 0
  return {
    begin() { return { generation: ++generation, identity: readIdentity() } },
    isCurrent(ticket) { return ticket.generation === generation && ticket.identity === readIdentity() },
    invalidate() { generation++ },
  }
}
