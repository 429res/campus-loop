# B-03 第二切片：参与者邀请确认与取消

本分支在原规则准备稿上集成 A-03 [PR #32](https://github.com/429res/campus-loop/pull/32)，继续使用 `feature/b03-invitation-rules` / [PR #31](https://github.com/429res/campus-loop/pull/31)。A-03 尚未合入 main 时，本 PR 依赖其先完成评审合入；不自动合并。前期纯规则验证记录不替代本轮正式事务证据，当前协作见 [Issue #30](https://github.com/429res/campus-loop/issues/30)。

## 当前能力和唯一调用链

- 正式创建继续走 B 的 `ExchangeApplicationService → A 的 DefaultExchangeCreationTransaction → ExchangeCycleValidator`，没有第二个创建器。
- 确认、取消和内部到期统一走 `ExchangeLifecycleService → ExchangeLifecycleRules`。创建与生命周期共用从 A-03 提取的 `ExchangeTransactionExecutor`（READ_COMMITTED、REQUIRES_NEW、20秒超时、瞬态冲突最多3次整事务重试）和 `ExchangeDatabaseClock`（数据库UTC、沿用TIMESTAMP秒精度）。
- `POST /api/exchanges/{id}/confirm`、`/cancel` 已实现，完成提交后回读参与者详情。本人列表/详情将参与记录作为邀请，返回当前动作；没有外部消息服务。
- A-04 后续只需在扫描中调用 `ExchangeLifecycleService.expire(id)`；目前已有共同事务入口，但没有定时扫描、批次重试和重启恢复联调。没有对外expire接口。交接仍501，不执行所有权转移。

## 已确认的状态与权限矩阵

发起人已明确采用：任一参与者在原24h截止前且无交接时可取消等待或READY，原因必填；每人独立确认，仅最后一人使交换READY。发起人不自动确认。确认和READY不重置创建时数据库UTC+24h的原截止，不增加隐式交接截止。

| 数据库事实 | 新确认 | 新取消 | allowedActions / 重试 |
| --- | --- | --- | --- |
| AWAITING_CONFIRMATION，本人未确认，未截止且无交接 | 本人可确认；version+1 | 任一参与者可取消；version+1 | CONFIRM、CANCEL |
| AWAITING_CONFIRMATION，本人已确认，未截止且无交接 | 重放，无事件/版本变化 | 仍可取消 | CANCEL |
| READY，未截止且无交接 | 已确认者只重放 | 任一参与者可取消，不限发起人 | CANCEL |
| 等待/READY且dbNow >= expiresAt | 409，包含确认重试 | 新取消409 | 空；共同到期入口可EXPIRED |
| 任一参与者已有handedOffAt或receivedAt | 409 | 409 | 空；到期也不释放，交接/争议留后续 |
| CANCELLED，原取消者且trim后相同原因 | 409 | 只读回原取消结果 | 空；允许旧version、拒绝未来version，截止后也不再次释放 |
| CANCELLED的其他人/不同原因；COMPLETED/EXPIRED/DISPUTED | 409 | 409 | 空；不覆盖历史、不复活 |
| 非参与者，包括ADMIN | 404 | 404 | 私有详情同样404，不提供代确认 |
| 缺少V8创建依据的旧记录 | 409 | 409 | 继续可读，空动作；不推测旧记录可安全释放 |

会话和参与者来自服务端；锁内再次检查操作人ACTIVE。allowedActions由相同纯规则和持久参与事实生成，只描述当前动作资格；请求仍须鉴权并在锁内核对资源完整性。并发变化、占用/版本损坏均可能使已展示的动作返回409，按钮不能代替鉴权。

## 版本、截止和事务顺序

1. 校验交换可见性，锁定exchange，再按用户ID升序锁参与者；新动作继续按需求ID升序、物品及占用ID升序，保持A-03顺序。需求冻结和创建重放不反向锁exchange。
2. 仅新变化要求当前version；确认重试须状态/无交接/截止仍允许，已成功取消的同actor+trim后同reason精确重放无写入。未来version409，缺失/负数/非int32 JSON整数400。新版本耗尽409。
3. 所有必要锁取得后重新读取数据库UTC并计算决策，使用该锁内授权时刻；请求到达时间、UI倒计时或等待前的时间不授权写入。等于截止即拒绝新用户动作，GET不自动过期。
4. 取消先提交，确认不能复活READY；确认先提交，旧version取消409，用户刷新后重新决定。交换锁同时串行确认、取消、到期；精确重试不重复审计、版本或释放。
5. 锁内核对持久参与流向、A-03创建快照中的原itemVersion、当前owner/RESERVED/version、完整占用集合及每条占用exchange_id、其他进行中引用。归属/行数/版本异常409。只删除 `item_id=? AND exchange_id=?`；物品条件恢复AVAILABLE、version+1，保留owner和reviewBasis。
6. 参与者确认时间、交换状态/版本、取消审计、生命周期事件和占用释放均在同一事务。任一步失败整笔回滚；无独立释放服务，不把物品履历当作邀请事件。

## V9迁移和兼容

A-03 V8 明确后续从V9协调，已在Issue #30同步 `V9__exchange_lifecycle.sql`，V1–V8未改写。复用原exchange/participant/hold表；新增exchange的cancelled_by、cancellation_reason、cancelled_at，以及只追加的cl_exchange_event。事件保存actor、动作、前后状态/版本、原因和数据库UTC；unique(exchange_id,new_version)保证每次变化一个事件。取消者及事件有非级联外键。

旧记录新增字段为NULL，没有伪造取消人或历史事件。V8创建快照、需求引用、物品、占用和旧字段原样保留。终态取消后所选需求按A-03既有逻辑解除进行中冻结，历史引用/快照继续保留；推荐查询仍无副作用，independent-v2规则不变。

## 给D-03/C-03的接口与恢复

- `POST /api/exchanges/{id}/confirm`：严格 `{"version":0}`。
- `POST /api/exchanges/{id}/cancel`：严格 `{"version":1,"reason":"课程时间冲突"}`，reason trim后1–1000字。
- 请求不接受actor/participant/status/confirmedAt/deadline/owner，也不接受查询参数。成功与精确重放返回200及当前ExchangeView。
- ExchangeView增量：`cancelledBy`（用户ID）、`cancellationReason`、`cancelledAt`（UTC ISO8601），未取消为NULL；历史审计事件留在持久存储，不另设用户可写事件接口。其余详情字段、分页及隐私不变。
- 400保留输入并修正；401恢复会话；403操作人锁内已停用；404停止操作、不泄露他人交换；409保留原因及原version，GET详情后由本人重新决定，不能自动换version重试。结果不确定时按exchangeId回读，或以原请求精确重试。
- C的管理交换查询尚未注册，不能把管理员视为参与者；D仍需接入按钮/刷新交互。当前handoff的501不能显示成功。

[虚构API样例](examples/b03-invitation-rules.json)包含两方/三方确认、READY取消、重试和冲突恢复；采用相同规则和真实接口字段，不包含令牌或真实用户数据。

## 验证与剩余范围

扩展同一 `ExchangeDomainIntegrationTest`，复用A-03真实创建、SQL探针、事务等待与回滚检查。完整命令、最终数量、MySQL及迁移升级结果见[验证记录](verification.md)和本PR；纯规则/H2不单独证明锁行为。清理仅处理本轮虚构数据并比较原业务行，临时MySQL由脚本自动删除。

本轮只完成B-03第二切片后端。交接、所有权转移、C/D页面接入、A-04扫描/恢复及浏览器/微信联调仍待后续，不标记整个B-03或A-04完成。
