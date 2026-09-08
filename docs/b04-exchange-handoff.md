# B-04 交接与原子流转

## 已确认契约（2026-09-08）

用户已确认：每名参与者分别声明 HANDED_OFF（已交出本人 offeredItemId）与 RECEIVED（已收到 receivedItemId）。物品 ID、对方、身份及时间由服务端从持久流向和数据库 UTC 得出。凭据为明确声明及可选文字说明，不要求上传或外部通知，不声称核验了实物。

POST /api/exchanges/{id}/handoff 请求为 `{"version":2,"kind":"HANDED_OFF","acknowledged":true,"note":"当面交出"}`。kind 仅 HANDED_OFF/RECEIVED；note 可省略，trim 后0–1000字；禁止 owner、itemId、actor、时间等未知字段。每人每类只能记录一次，成功后同类同说明可用旧但非未来 version 精确重放，不改时间或事件；不同说明409，修正留后续履历追加能力。重放可发生在 COMPLETED/DISPUTED，不能推进状态；新动作须当前 version。

仅 READY 可交接，首次声明必须 now < 原 expiresAt；任何 handed_off_at/received_at 是共用的交接开始标识。开始后允许超过原截止继续交接，取消和 A-04 自动到期均停止。两人须4次、三人须6次声明，最后一次才在同一事务更新所有权、物品 EXCHANGED/version+1、精确引用需求 INACTIVE/version+1、追加每件物品的 EXCHANGED 履历、释放本交换占用并 COMPLETED。EXCHANGED 保持既有公开详情可见性，但不可交换、不可作为推荐候选；不自动再次上架。

POST /api/exchanges/{id}/dispute 请求 `{"version":3,"reason":"收到的物品与约定不符"}`，仅 READY 且已开始交接的本人参与者可登记；原因trim后1–1000字，允许原截止后登记。原子进入 DISPUTED、记录操作者/原因/时间和事件，保持所有权、需求和占用。原登记者相同原因可用旧但非未来版本重放；其他终态写入409。裁决、代办、强制释放与争议后完成均未实现。

## A/B 主责及迁移

A-03创建、B-03确认取消、A-04扫描、B-04交接争议共用 ExchangeLifecycleService、ExchangeTransactionExecutor 与 ExchangeLifecycleRules。交换首锁 → 用户升序 → 需求升序 → 物品及占用升序；最终数据库 UTC 决定首次交接边界。锁先取得者按当前状态和版本决定后续动作，没有客户端时间优先权。A-04 PR #34已合入 feature/b03-invitation-rules；本分支包含该依赖，不自动合并 main。

V11 新增：参与者交接说明、交换争议审计、新审计事件类型；V1–V10不改。旧无创建依据交换仍只读，不补造交接事实。

需求只关闭 cl_exchange_demand 精确选中记录：校验其创建引用、版本、当前所有者及 ACTIVE 状态、关联的本人提供物品；其他进行中引用冲突则整笔409。其他需求及旧 wanted 字段不双写、不清空。B-05复用 cl_item_history EXCHANGED 追加记录（来源提供者、对方接收者、数据库发生/记录时间、BOTH_CONFIRMED，管理员与核验时间为空），本轮无履历覆盖或管理员核验入口。

## 状态与权限矩阵

| 当前事实 | 新交接声明 | 取消 | 自动到期 | 登记争议 |
| --- | --- | --- | --- | --- |
| AWAITING_CONFIRMATION | 否，先完成邀请确认 | 截止前 | 截止时及以后 | 否 |
| READY，尚无声明，截止前 | 参与者的未提交类别 | 任一参与者 | 否 | 否 |
| READY，尚无声明，已到截止 | 否 | 否 | 是 | 否 |
| READY，已开始交接 | 参与者的未提交类别，不受原截止限制 | 否 | 否 | 任一参与者 |
| COMPLETED | 仅本人同类同说明重放 | 否 | 否 | 否 |
| DISPUTED | 仅本人已提交声明重放，不再推进 | 否 | 否 | 仅原登记者相同原因重放 |
| CANCELLED/EXPIRED | 否 | 仅既有取消契约的精确重放 | 无变化 | 否 |

同一交换锁串行。并发的两个不同新声明携带同一 version：一个成功，另一个409；必须刷新后由用户确认剩余声明。最后声明与争议登记互斥：先成功者改变状态/版本，后者409。重复同一声明即使最后流转已提交也只读重放。参与者先交出或先收到均允许，是两个独立事实声明，不由一个人的操作推断他人的事实。

## 给 D-03 / C-03 的虚构样例

所有请求使用当前登录身份。下面ID和时间仅用于说明，不能直接当真实数据库记录调用。统一响应外壳 `{code:200,message:...,data:...}` 沿用 ResultVo；示例展示 data，错误使用真实HTTP状态，不当200。

双方：101提供201给102，102提供202给101；命中需求301（102）和302（101）。邀请全确认后READY/version2。依序提交101 HANDED_OFF v2→v3、102 RECEIVED v3→v4、102 HANDED_OFF v4→v5、101 RECEIVED v5→COMPLETED/v6。前三次owner不变、两条占用保留；最后201归102、202归101、两件EXCHANGED，301/302 INACTIVE。其他需求完全保留。

```json
{"version":2,"kind":"HANDED_OFF","acknowledged":true,"note":"已当面交给对方"}
```

该请求 POST /api/exchanges/41/handoff 后，101收到的详情示例：

```json
{
  "id":41,"initiatorId":101,"status":"READY","version":3,
  "createdAt":"2026-09-08T08:00:00Z","expiresAt":"2026-09-09T08:00:00Z",
  "participants":[
    {"userId":101,"displayName":"虚构同学甲","offeredItemId":201,"receivedItemId":202,"confirmationStatus":"CONFIRMED","confirmedAt":"2026-09-08T08:01:00Z","handedOffAt":"2026-09-08T09:00:00Z","receivedAt":null,"handedOffNote":"已当面交给对方","receivedNote":null},
    {"userId":102,"displayName":"虚构同学乙","offeredItemId":202,"receivedItemId":201,"confirmationStatus":"CONFIRMED","confirmedAt":"2026-09-08T08:02:00Z","handedOffAt":null,"receivedAt":null,"handedOffNote":null,"receivedNote":null}
  ],
  "flows":[{"itemId":201,"fromUserId":101,"toUserId":102},{"itemId":202,"fromUserId":102,"toUserId":101}],
  "allowedActions":["RECEIVED","DISPUTE"],
  "cancelledBy":null,"cancellationReason":null,"cancelledAt":null,
  "disputedBy":null,"disputeReason":null,"disputedAt":null
}
```

三方：101提供201给102，102提供202给103，103提供203给101。页面分别给101显示“交出201／收到203”，102“交出202／收到201”，103“交出203／收到202”。READY/version3后：101交出v3、102收到v4、102交出v5、103收到v6、103交出v7、101收到v8；响应依序version4–9，第六次COMPLETED。201→102、202→103、203→101，三条精确需求关闭、三件履历追加、三条占用释放；前五次均不转移。不能把三方流向当成两两互换。

争议：READY/version4已开始交接，POST /api/exchanges/41/dispute `{"version":4,"reason":"收到物品的状态与约定不符"}` → DISPUTED/version5、disputedBy为当前用户、disputedAt为数据库UTC、allowedActions=[]。继续展示原流向和已提交声明；物品保持RESERVED和原owner，占用保留。没有裁决、恢复交接或强制释放按钮。

| 错误/刷新场景 | HTTP | D 的恢复方式 / C 的边界 |
| --- | --- | --- |
| 未登录或会话失效 | 401 | 重新登录后GET本人详情；不自动补交实物声明 |
| 非参与者，包括非参与管理员 | 404 | 不披露详情，不代办 |
| 登录参与者账号不可用 | 403 | 不提交操作 |
| acknowledged不是true、未知字段、非法kind/version/note/reason | 400 | 修正原表单，不增加owner/itemId字段 |
| stale/future version、非READY、首次已截止、资源或需求引用变化 | 409 | 保留说明，GET刷新；用户核对后再提交，不能自动换version重试 |
| 网络超时但服务器已提交 | 200精确重放或409 | 可重发同类同说明和原version，再GET；不重发另一类别来猜测状态 |
| GET仍READY但截止已到且无交接 | 200、allowedActions=[] | 等A-04处理后刷新，不本地改EXPIRED |

详情里的交接说明仅参与者可见；没有公共证据附件URL，私人需求和创建快照不回传。C-03管理读取仍按既有待实现契约，不因本轮增加管理员查看私人凭据或代交接权限。B-05可复用 ExchangeLifecycleMapper.history 的原子追加入口及 cl_item_history：event_type=EXCHANGED，source_user_id=提供者，counterparty_user_id=接收者，occurred_at/recorded_at/confirmed_at=最终数据库UTC，evidence_level=BOTH_CONFIRMED，verified_by_user_id/verified_at为空。两边确认时间和说明在持久参与者记录，各次声明与争议在 cl_exchange_event；最后 HANDED_OFF/RECEIVED 事件的new_status=COMPLETED，而不是另加一个重复版本事件。

## 实施与证据

2026-09-08 本分支实际运行：

- `node scripts/run.mjs test`：用户端49项通过；公共规则72项通过；API127项中113通过、14项真实MySQL专用明确跳过，无失败（后端实际185通过）。初轮修正旧501/allowedActions预期与测试夹具参数后重跑通过。
- `CAMPUS_TEST_PORT=3337 node scripts/mysql-test.mjs`：全新临时MySQL8.4.11，Flyway V1–V11；后端199项全部通过，无跳过。共用ExchangeDomainIntegrationTest 46项全部通过，其中B-04增加8项；沿用A-03真实创建、A-04扫描/进程恢复及SQL探针，不复制创建/并发实现。最终源码中的版本上限防护亦在本次完整MySQL回归范围。
- 真实数据库证据包括：2/3人4/6声明、最终一次owner转移/精确需求关闭/参与者可信履历；不同最后确认同版本一个成功另一个409，刷新后完成；相同最后确认并发精确重放不重复流转；取消先/后、到期先/交接后、争议与最后确认互斥；真实行锁等待跨截止后首次交接409；需求版本/状态/引用或外部占用改变不写入。七个写阶段（receive、transition、event、fulfill、transfer、history、release）分别注入真实SQL唯一约束失败，整套物品/需求/参与者/事件/履历/占用记录逐字段比较保持不变，移除故障后重试成功。
- `CAMPUS_TEST_PORT=3337 node scripts/mysql-test.mjs --item-review-upgrade`：另一个全新临时MySQL，1项通过；逐级升级包括V10→V11，保留交换状态/版本/截止/元数据、既有确认事件、参与者交接时间和占用，新增说明/争议字段为空，不伪造历史。
- 两次容器均自动停止移除；未连接成员日常库，未记录凭据。保留既有Flyway对MySQL8.4支持版本的提示，本片无依赖升级。仓库交付检查和74处文档相对链接检查通过。

无页面改动；浏览器、微信真机和D/C页面联调未执行。争议裁决、履历修正/自述和管理员核验留后续，不标记完成。功能分支待其他成员审核与CI，不自动合并main。
