export const STATS_LOAD_STATE = Object.freeze({
  IDLE: "idle",
  LOADING: "loading",
  READY: "ready",
  ERROR: "error",
});

export function metricPresentation({ state, value, unavailable = false }) {
  if (state === STATS_LOAD_STATE.ERROR)
    return { value: "读取失败", state: "error" };
  if (unavailable) return { value: "—", state: "unavailable" };
  if (state !== STATS_LOAD_STATE.READY)
    return { value: "—", state: "loading" };
  return Number.isFinite(value)
    ? { value: String(value), state: "ready" }
    : { value: "读取失败", state: "error" };
}

export function createLatestRequestTracker() {
  let sequence = 0;
  let controller;
  return {
    start() {
      controller?.abort();
      controller = new AbortController();
      const requestSequence = ++sequence;
      return {
        signal: controller.signal,
        isLatest: () => requestSequence === sequence,
      };
    },
    cancel() {
      sequence += 1;
      controller?.abort();
    },
  };
}
