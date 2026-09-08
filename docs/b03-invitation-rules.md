# B-03 第二切片：邀请确认与取消规则准备

2026-09-08，分支 `feature/b03-invitation-rules`，同步main `c8e9185`。依赖核查：B-03.1 [PR #28](https://github.com/429res/campus-loop/pull/28)已合入，提供领域入口及本人读取，A-03正式创建/唯一占用尚无实现，A-04也未实现。发起人明确选择“先由A完成A-03；B先整理本片契约与独立验证”。因此本PR不接入生产写动作，不标记B-03.2完整验收。协作见 [Issue #30](https://github.com/429res/campus-loop/issues/30)，创建边界延续 [Issue #25](https://github.com/429res/campus-loop/issues/25)。

## 当前交付

`sys-project-com`新增单一 `ExchangeLifecycleRules` 纯规则，确认、取消、A-04到期决策及未来allowedActions共用同一状态/时间判断。它只接收不可变数据库事实与显式databaseNow，返回下一快照、一个可选事件和可选releaseExchangeId；没有Mapper、事务、时钟读取、定时任务、消息发送或物品写入。null事件表示重放/不操作，不能据此再次写审计或释放。

合法创建命令及已认证的confirm/cancel/handoff仍返回501。纯规则算出的permittedActions不能直接开放按钮：生产能力尚未接入，实际可执行动作仍为空；B-03.1本人列表/详情已真实可读，当前allowedActions始终为空。没有新增取消/超时服务、表或迁移号，不给A另造一套实现。邀请沿用交换参与者及本人查询，不启用外部消息服务。

## 已确认的状态与权限矩阵

发起人已明确采用：任一参与者在原24h截止前、未交接时可取消AWAITING_CONFIRMATION或READY；原因必填；截止及以后拒绝新确认/取消；重复操作不新增事件；同一交换锁串行，新变更检查version。原截止来自创建时数据库UTC+24h，确认/进入READY不延长；本切片不引入独立交接截止。

| 数据库事实 | 新确认 | 新取消 | 重试/到期含义 |
| --- | --- | --- | --- |
| AWAITING_CONFIRMATION、本人未确认、未截止、无交接 | 本人可确认；仅全员确认才READY | 任一参与者可取消 | 成功各增version一次 |
| AWAITING_CONFIRMATION、本人已确认、未截止、无交接 | 重放，不写事件/版本 | 仍可取消 | 按本人确认记录识别，不替别人确认 |
| READY、未截止、无交接 | 已确认者只重放 | 任一参与者可取消，不限发起人 | 新取消仍须当前version |
| AWAITING_CONFIRMATION/READY，dbNow >= expiresAt | 409，包括确认重试 | 新取消409 | A-04可作EXPIRED决策；用户请求不偷偷过期或释放 |
| 任一参与者已有handedOffAt或receivedAt（汇总handoverStarted） | 409 | 409 | 到期也不直接释放/恢复AVAILABLE，后续争议/交接规则处理 |
| CANCELLED，原取消者且trim后相同原因 | 409 | 只重放原取消结果 | 可在原截止后重放；无新事件/版本/释放 |
| CANCELLED，其他取消者或不同原因 | 409 | 409 | 不覆盖取消审计；历史记录缺取消者/原因也不能伪造重放 |
| COMPLETED/EXPIRED/DISPUTED | 409 | 409 | 到期决策不操作、不重复释放 |
| 非参与者（包括ADMIN） | 404 | 404 | 与私有详情相同，不暴露记录存在性或提供代确认 |

confirmedAt与各参与者身份来自服务端持久记录；发起人不自动确认。身份必须经现有认证和事务内必要重校验；纯规则没有管理员特权参数。终态状态表中的取消精确重试是一次读回，不是再次终态写入。

## 版本、截止与竞态优先规则

1. 先以会话身份判定参与者；再检查version类型及不能为未来版本。仅新状态变更要求expectedVersion等于当前version。缺失/负数400，未来/过期版本或版本耗尽409。
2. 确认先检查当前可操作状态、无交接和严格 `databaseNow < expiresAt`，再识别本人已确认重放；合法重放允许旧version，不新增事件。取消先识别同取消者/同原因的精确CANCELLED重放，其余请求检查状态/交接/截止并执行版本条件更新。
3. 同一exchange行锁串行。取消先提交则后续确认不能复活READY；确认先提交则旧version取消409，即使状态刚变READY也不能覆盖。客户端显式刷新后，可按新状态/版本和原截止重新决定是否取消，不自动重试。
4. A-04到期只在未交接的AWAITING_CONFIRMATION/READY且 `databaseNow >= expiresAt` 时产生EXPIRED事件；其他状态、未截止、已交接均不操作。confirm/cancel与expire不能互相释放或重复写事件。纯顺序推演不是并发锁证明。
5. 时间来自取得所需锁后的数据库UTC，不能用请求到达时间或应用机器时间绕过截止；等待物品锁后若已跨过截止必须重新判断。取消已经提交后，即使重试发生在原截止之后，精确重放不再变更或延长时间。

## A-03/A-04 后续唯一事务接入要求

未来确认、取消、到期必须共用一个生命周期事务服务，在锁内调用本纯规则并原子应用决策。已有动作exchange行锁在前，再按与A-03一致的需求/物品ID升序锁定；用户锁、幂等和外键隐式锁仍需A完成完整MySQL验证，不能把本顺序说明当作已验证实现。

取消/到期使用同一条件释放路径：只处理当前exchange持有的预期item集合，删除限定 `item_id=? AND exchange_id=?`，同时验证物品原owner/status/version及引用状态后条件回写；不无条件AVAILABLE，不把其他交换占用删掉。任一归属、行数或版本异常整笔回滚，交换状态、参与者、取消审计、物品及占用不能只成功一部分。检查到handedOffAt/receivedAt时不能走直接释放。

取消记录须持久保存actor、trim后原因、数据库UTC时间，事件包含前后状态/版本；每次新确认只记录一次本人的确认事件，最后确认事件携带到READY的状态变化即可。重复确认/取消/到期不追加重复事件。V2没有取消审计列/事件存储；A协调下一迁移及唯一约束，当前未预占序号、没有DDL。完整时钟提供器、事务事件持久化、条件释放、A-04扫描/批次/重试/重启恢复均未实现。

## 给D-03/C-03的接口草案与恢复

以下是未来接入DTO，**本轮未注册这些DTO或成功处理逻辑**；同名原预留入口当前始终501：

- `POST /api/exchanges/{id}/confirm`：`{"version":2}`。
- `POST /api/exchanges/{id}/cancel`：`{"version":2,"reason":"课程时间冲突"}`；reason trim后1–1000字。
- 禁止提交actor/participant/status/confirmedAt/deadline/物品归属；未来DTO须严格字段及JSON整数校验。现有501控制器不代表这些校验已经可用。
- 未来成功/精确重放返回当前ExchangeView，所有时间、状态、version、allowedActions均来自服务端；计划增量字段cancelledBy/cancellationReason/cancelledAt。releaseExchangeId是内部决策字段，不能暴露为客户端释放指令。

完整虚构[规则与错误样例](examples/b03-invitation-rules.json)标注了当前501响应和纯规则演算，未放置假HTTP200记录。未来恢复：400保留输入并修正；401统一恢复会话；404停止操作、不透露他人交换；409保留原因和原version，GET详情后由本人重新核对，不自动换version再次提交；501维持待开发入口，不能将本地决策变成成功记录。请求结果不确定时按exchangeId刷新本人详情；确认已记录或取消者/原因一致后才视为完成，不靠按钮消失判断。

未来allowedActions来自permittedActions并叠加真正可用的事务能力。未确认参与者在可操作窗口为CONFIRM/CANCEL，已确认和READY为CANCEL，截止/交接/终态/非参与者为空。管理员仅有独立管理读权限，不得到代确认权；C可展示取消人/原因/时间和真实审计，但不能调用内部expire代替A-04任务。D当前已合入预览PR #27继续只读，不开放501按钮。

## 验证范围

本次新增17项纯规则测试，覆盖2/3人到READY、未全确认等待、重复确认/取消事件为空、READY取消、终态/非参与者/交接拒绝、version耗尽及确认/取消/截止的顺序推演和纳秒边界。新增2项现有隔离后端套件测试：实际数据库UTC作为规则时间输入；已认证/未认证预留动作501/401及六张业务表逐行无写入。未手写交换创建器或模拟占用争抢。

自动化与真实MySQL的最终结果见本PR及verification记录。数据库测试只能证明实际时钟输入、既有迁移/回归和当前无写入边界，不能证明正式创建或确认/取消事务竞态、唯一释放、回滚、定时扫描恢复。A-03到位后必须用真实创建再走2/3人READY、竞争取消及占用归属验收，并复用A-04共用事务测试。

本轮未执行浏览器/微信交互，没有生产API/依赖/迁移改动。仅更新B-03.2的规则准备状态，不标记B-03整体、A-03或A-04完成。PR不自动合并main。
