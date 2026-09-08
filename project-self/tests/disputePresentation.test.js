import test from "node:test";
import assert from "node:assert/strict";
import { buildParticipantRows, disputeImpactFacts, disputeLoadError } from "../src/features/disputes/disputePresentation.js";

test("participant rows follow persisted flows rather than current ownership", () => {
  const rows = buildParticipantRows({ participants:[{userId:2,displayName:"乙"},{userId:1,displayName:"甲"}], flows:[{itemId:11,fromUserId:1,toUserId:2},{itemId:22,fromUserId:2,toUserId:1}] });
  assert.deepEqual(rows.map(({userId,offeredItemId,receivedItemId})=>({userId,offeredItemId,receivedItemId})), [{userId:2,offeredItemId:22,receivedItemId:11},{userId:1,offeredItemId:11,receivedItemId:22}]);
});

test("DISPUTED impact states preservation and no invented rollback", () => {
  const facts = disputeImpactFacts({status:"DISPUTED"}).join(" ");
  assert.match(facts,/所有权保持/);
  assert.match(facts,/占用继续保留/);
  assert.doesNotMatch(facts,/释放|回滚所有权/);
  assert.deepEqual(disputeImpactFacts({status:"READY"}),[]);
});

test("read errors keep authorization and refresh semantics", () => {
  assert.match(disputeLoadError({status:403}),/权限/);
  assert.match(disputeLoadError({status:409}),/重新读取/);
});
