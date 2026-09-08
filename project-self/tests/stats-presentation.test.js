import test from "node:test";
import assert from "node:assert/strict";
import { createLatestRequestTracker, metricPresentation, STATS_LOAD_STATE } from "../src/features/stats/statsPresentation.js";

test("real zero remains zero while unavailable and failures stay distinct", () => {
  assert.deepEqual(metricPresentation({ state: STATS_LOAD_STATE.READY, value: 0 }), { value: "0", state: "ready" });
  assert.deepEqual(metricPresentation({ state: STATS_LOAD_STATE.READY, value: null, unavailable: true }), { value: "—", state: "unavailable" });
  assert.deepEqual(metricPresentation({ state: STATS_LOAD_STATE.ERROR, value: null }), { value: "读取失败", state: "error" });
});

test("missing numeric values are not fabricated as zero", () => {
  assert.equal(metricPresentation({ state: STATS_LOAD_STATE.READY, value: null }).state, "error");
  assert.equal(metricPresentation({ state: STATS_LOAD_STATE.LOADING, value: 9 }).state, "loading");
});

test("a newer refresh aborts and invalidates the previous request", () => {
  const tracker = createLatestRequestTracker();
  const first = tracker.start();
  const second = tracker.start();
  assert.equal(first.signal.aborted, true);
  assert.equal(first.isLatest(), false);
  assert.equal(second.isLatest(), true);
  tracker.cancel();
  assert.equal(second.signal.aborted, true);
  assert.equal(second.isLatest(), false);
});
