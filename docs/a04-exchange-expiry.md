# A-04：交换超时扫描与恢复

原交付分支 `feature/a04-exchange-expiry` 基于 B-03.2 的 `6025f27`，包含 A-03 [PR #32](https://github.com/429res/campus-loop/pull/32) 和 B-03.2 [PR #31](https://github.com/429res/campus-loop/pull/31)；原任务开始时两者均未合入 main，A-04 因而以 B 分支为 PR 基线。

2026-09-08 阶段二审查状态：A-03/B-03.2 已合入 main；审查期间 [PR #36](https://github.com/429res/campus-loop/pull/36) 将 A-04 与 B-04 合入 main `a7cdba5`，本修复分支已对齐该基线。以下机制已同步本轮候选扫描修复；原交付验证证据单独保留，不当作本轮重跑结果。

## 唯一事务边界

`ExchangeExpiryScheduler → ExchangeExpiryScanner.scanBatch() → ExchangeLifecycleService.expireForScan(id)`。确认、取消、交接、内部expire和扫描expireForScan共享同一个applyLocked、ExchangeLifecycleRules、ExchangeTransactionExecutor与ExchangeDatabaseClock。候选选择先用独立短事务执行 `FOR UPDATE SKIP LOCKED`，结束后释放候选锁；逐条正式处理仍进入共同生命周期事务，以同样的 exchange 首锁跳过竞争行，再重锁、重读并核验业务事实，不复制状态判断或释放代码。

exchange锁在前，随后参与用户ID升序→需求ID升序→物品/占用ID升序；所有必要锁取得后重新读取数据库UTC，按纯规则决定能否变更。原创建快照、参与者、owner、RESERVED状态、物品version、完整占用集合和每条占用exchange_id均须一致。仅删除 `item_id=? AND exchange_id=?`，条件恢复AVAILABLE/version+1；状态/version、EXPIRED审计事件、全部占用释放与物品恢复同事务提交。任一环节失败全部回滚。确认和取消沿用B现有真实HTTP入口；原 A-04 交付时尚未实现的交接写入，已由 [B-04](b04-exchange-handoff.md) 沿用此 exchange 首锁与交接事实规则，并随 PR #36 合入 main。

## 截止与结果：B/C/D共同契约

| 数据库事实 | 自动超时结果 |
| --- | --- |
| AWAITING_CONFIRMATION或READY，DB UTC >= 原expiresAt，无任何交接事实 | 原子变为EXPIRED，version+1，追加一次EXPIRED事件，actor/reason为null；仅释放本交换占用，物品保留owner并恢复AVAILABLE/version+1 |
| 尚未到原expiresAt | 不变；占用表的expires_at提前或客户端倒计时归零都不能授权释放 |
| 任一参与者handed_off_at或received_at非空 | 不自动超时、不重新上架；留给后续交接/争议流程 |
| COMPLETED/CANCELLED/EXPIRED/DISPUTED | 不变；重复扫描不新增事件、不重新释放 |
| 缺少V8规则/摘要/创建快照的旧记录 | 扫描排除，维持只读，不推断历史占用可释放 |
| 归属、version、占用集合或其他持久事实异常 | 全部回滚并延后重试，不能部分释放或删除其他交换的占用 |

创建时数据库UTC+24h仍是唯一截止，确认和进入READY不延长它。截止等号及以后确认/新取消409；GET始终只读，可能暂时返回过期但尚未由任务处理的AWAITING_CONFIRMATION/READY，此时allowedActions为空。D应显示“已到期，等待处理”并回读服务端状态，不能在本地伪造EXPIRED、取消成功或物品可交换。处理完成后详情/本人列表可读EXPIRED，allowedActions为空；不新增公共expire接口。

B继续用同一生命周期服务，重放旧取消/超时不会释放后来新交换的占用。C的管理读取仍需使用后续明确授权的管理接口，本轮不新增管理员代确认或强制释放能力。技术重试字段不进入ExchangeView、不给前端暴露数据库异常。没有向参与者发送外部通知；本轮通过共享契约/PR同步，未改页面或执行浏览器/微信联调。

## 批次、重试与持久恢复

每次候选选择在独立短事务内按 expires_at/id 排序，以 `LIMIT` 限制批次并执行 `FOR UPDATE SKIP LOCKED`，时间来自数据库。查询阶段即跳过正被锁定的旧记录，使可用的后续到期记录仍能进入有上限的批次；避免最旧的一整批长期忙碌时，反复选中同批再逐条跳过而饿死后续记录。短事务提交并释放全部候选锁后，才逐条进入正式处理事务，不把整批候选锁带入用户、需求和物品锁序。

候选只是线索，逐条生命周期事务重新锁定 exchange，并再次核查状态、截止、交接和占用；候选选出后可能已被其他动作改变，不能据候选结果直接释放。单条处理独立事务，前一条失败不撤销其他已成功条目。再次遇到忙碌 exchange 仍用 SKIP LOCKED 跳过，后续轮询重新发现；多实例可以同时扫描，exchange 锁与事件唯一约束保证只有一次有效转换。

生命周期沿用瞬态冲突最多3次完整事务重试、每次20秒事务预算。失败回滚后另开事务，仅更新技术字段：

- `expiry_retry_count` 累加并饱和，表示已记录的失败次数，不是交换业务version。
- `expiry_retry_at` 基于数据库UTC按30、60、120秒指数退避，最高3600秒；不会修改原expiresAt。
- `expiry_failure_code` 仅固定STATE_CONFLICT或PROCESSING_FAILED；不保存异常文本、SQL、请求、用户名或凭据。

更新重试记录也校验仍为已到期活动状态、无交接且没有未来退避；取消/到期已经成功则不覆盖终态。后续成功转换清除retry_at/failure_code，保留失败次数作为运维计数。失败条目有未来重试时间，不会占满每次返回批次而阻塞后续正常条目。技术记录写入本身失败时，业务行仍未成功转换且仍可被下轮发现。

进程内只有唤醒定时器，没有唯一任务队列、内存截止或已处理ID集合。扫描游标不跨进程保存；重启读取数据库全部到期且退避已到期的记录即可继续。进程在提交前退出由数据库回滚，提交后退出由终态和唯一事件使重试无操作。本轮实际进程测试覆盖停止旧JVM、启动新JVM自动处理持久待办与重试记录；中途释放回滚通过真实SQL约束故障验证，未将其描述成进程中途崩溃测试。

## 运维与迁移

仅新增 `V10__exchange_expiry_recovery.sql`：三项技术重试字段、非负计数约束、`(status,expires_at,id)`扫描索引；V1–V9不改。已有状态、截止、占用与审计保持，新字段NULL/0，不生成历史到期事件。先部署依赖A-03/B-03.2与V10，再启动应用；定时器在只执行bootstrap初始化时关闭。

| 后端环境变量 | 默认值 | 含义 |
| --- | --- | --- |
| CAMPUS_EXCHANGE_EXPIRY_ENABLED | true | 启用轮询；false仅停扫描，确认/取消和数据仍保留 |
| CAMPUS_EXCHANGE_EXPIRY_BATCH_SIZE | 50 | 每轮最多1–100条候选，越界启动失败 |
| CAMPUS_EXCHANGE_EXPIRY_DELAY_MS | 30000 | 上一轮完成后的轮询间隔，配置正整数 |
| CAMPUS_EXCHANGE_EXPIRY_INITIAL_DELAY_MS | 1000 | 应用启动后的首次轮询延迟 |

修改上述参数后重启后端生效。使用README现有 `node scripts/run.mjs backend` 启动；环境变量经原本机.env加载，不要求新服务或全局工具。单实例不重叠轮询；单条等锁/重试可能延迟整批，轮询间隔不是硬实时到期保证。增大批次前观察数据库锁等待，不能据前端倒计时承诺精确释放时刻。暂停后重新开启或重启会恢复数据库待办，不需重建任务。

日志只输出成功数、选取数、延期数、重试记录失败数。空批次不刷日志；扫描失败只给固定提示、不带异常参数或堆栈。数据库故障恢复后后续轮询继续。运维可在已授权数据库连接执行下列只读查询查看积压，避免读取私人创建快照：

```sql
SELECT id,status,expires_at,expiry_retry_at,expiry_retry_count,expiry_failure_code
FROM cl_exchange
WHERE status IN ('AWAITING_CONFIRMATION','READY') AND expires_at <= UTC_TIMESTAMP()
ORDER BY expires_at,id;
```

重试数持续增加说明需要排查具体状态/占用完整性或数据库可用性；不能靠删除占用或强制AVAILABLE跳过领域检查。上面查询包含交接中和旧记录，须结合状态矩阵区分自动候选；它们本来就不会被普通超时释放。对已明确修复的问题，等待持久retry_at后由常规扫描重试，不重置业务version或截止。

## 原 A-04 交付历史证据（2026-09-08）

以下为原 A-04 交付时记录的最终验证，尚未包含本轮候选短事务修复或本地整合 B-04 后的重跑结果；本轮综合证据由阶段二审查报告单独记录：

- `node scripts/run.mjs test`：用户端49项通过；公共纯规则单元69项通过，API/功能119项中107通过、12项MySQL专用明确跳过，无失败。
- `CAMPUS_TEST_PORT=3335 node scripts/mysql-test.mjs`：全新MySQL8.4.11，后端69+119共188项全部通过，共用交换套件38项；其中8项是本轮新增扫描/重试/恢复测试。实际输出确认两个独立JVM自动恢复且仅一条EXPIRED事件；本轮新进程同时恢复普通到期与已持久化失败重试记录。
- `CAMPUS_TEST_PORT=3335 node scripts/mysql-test.mjs --item-review-upgrade`：独立新空库1项通过，V9→V10保留旧字段与占用。两个最终容器均自动停止删除，测试子进程强制终止并清理日志。
- 仓库交付检查与相关文档83处相对链接检查通过。没有页面或依赖改动，未执行浏览器/微信/真机或成员日常库联调。

沿用B的ExchangeDomainIntegrationTest和原临时MySQL脚本，没有另建测试创建器。

- 单元/功能：共用纯生命周期规则回归；扫描状态矩阵、批次上限、持久退避、后续正常记录继续处理、固定错误码和日志脱敏。
- 数据库：真实MySQL两扫描者SKIP LOCKED、确认/取消与超时互斥、旧/他人占用保护、transition/event/release/restore各阶段唯一约束异常后的整体回滚、原任务重试一次成功。
- 运行恢复：两个独立JVM、旧进程强制停止、新进程启用真实定时器，自动恢复持久到期记录和退避已到期的失败记录；新进程采用Pacific/Honolulu时区，结果仍由数据库UTC决定。没有手动调用新进程的scan方法。
- 迁移：独立空MySQL执行V9→V10，逐字段保留已有状态/截止/元数据/占用；没有clean/drop业务库。所有夹具和子进程清理，临时容器自动删除。

初轮H2检查发现TIMESTAMPADD未定型时间参数不兼容，补为显式DATETIME后重新执行完整单元/功能、MySQL及迁移检查，上述为最终结果。既有Flyway对MySQL8.4的版本支持提示保留，本次迁移与运行均通过，没有夹带依赖升级。
