# A-03：共用交换创建事务

分支 `feature/a03-exchange-transaction`，基于main `c8e9185`，保留原工作区未提交改动。B-03 [PR #28](https://github.com/429res/campus-loop/pull/28) 已合入，本次直接接通其端口；核对了 `feature/b03-invitation-rules` 的 `31bfd7c`，该分支只有后续生命周期纯规则，没有另一条创建写路径或预占迁移。创建政策沿用B已确认的全员不自动确认、数据库UTC+24h、independent-v2及进行中需求冻结。

## 服务与集成

```java
// B 的 ExchangeApplicationService 调用；initiatorId 由 ExchangeController 从认证会话取得。
public interface ExchangeCreationTransaction {
    long create(long initiatorId, ExchangeCreationCommand command);
}
```

唯一实现为 `DefaultExchangeCreationTransaction`。B的严格DTO、规范化摘要、ExchangeCycleValidator、IndependentDemandMatcher、本人列表和详情全部复用。没有第二套交换状态机、推荐写入器、占用表或旁路HTTP入口；控制器不承担事务。端口每次返回已提交的交换ID，B提交后回读ExchangeView。服务内部不接受客户端用户、参与者、角色、状态、评分或截止时间。

**B创建入口实际集成完成**：通过POST真实认证/MVC/事务/Mapper创建2人和3人交换，再用B现有查询读取。B-03第二片的确认/取消、B-04交接、A-04超时仍未实现，本轮不接入其纯规则、不开放这些动作；allowedActions仍为空。D/C页面没有接入本次创建能力，未执行浏览器、微信工具或真机联调。创建不代表完成整条交换闭环。

## 锁与事务

1. 每次创建尝试使用独立READ_COMMITTED事务。无锁物品查询只发现owner锁线索；按用户ID升序锁定当前所有者与发起人，事务内确认发起人ACTIVE。
2. 用户锁也串行化 `(initiator,idempotencyKey)`。先查已有交换并比较B的SHA-256摘要：相同立即返回原ID；不同409。不会因第一次提交的RESERVED/版本递增而拒绝相同重试，也不延长截止。所有者发现发生漂移时，新请求在物品锁内拒绝，不临时追加乱序用户锁。
3. 新请求检查所有参与用户ACTIVE；按完整有效关联需求与请求需求ID升序锁定，再按物品ID升序锁物品及已有占用。需求创建、编辑、启停、删除都先锁本人的用户行，因此新增需求、未选需求的标签/分类/状态变化以及关联替换均不能在锁内改变选择输入。需求的分类选择锁仍在物品之后。
4. 在这些锁内重新读取并调用B领域校验器：物品AVAILABLE、无任何占用（含过期行）及进行中引用、版本有效、用户/物品唯一、2/3环、每人一件、接收需求ACTIVE且关联自己的环内提供物品。分类硬匹配，标签仅用于原规则选需求/排序；不能只核对客户端选中需求而漏掉其他更优需求，也不悄悄更换所选需求。
5. 同事务写AWAITING_CONFIRMATION/version=0、全员未确认的参与者、精确需求引用/快照、唯一占用，再按物品升序条件写RESERVED/version+1；保留owner、reviewBasis和审核审计。锁后取数据库UTC时间，expiresAt=createdAt+24h；生产MySQL连接会话固定UTC，H2分支仅服务功能测试。
6. 唯一/FK约束失败在整笔回滚后转409，不保留幂等记录、参与者、引用、占用或物品版本。数据库瞬态冲突最多尝试三次，每次重新开始完整事务，耗尽409；客户端继续使用原逻辑提交的键。

需求冻结在需求锁内通过READ_COMMITTED查询AWAITING_CONFIRMATION/READY/DISPUTED引用，禁止编辑、启停、删除；不锁已有exchange。终态后仍可按原需求接口修改，历史引用和快照不变。未选中的需求不冻结。后续B/A-04动作先锁已有exchange，再保持同样的用户/需求/物品顺序；创建重放和冻结查询不反向等待已有exchange锁。账号管理的既有锁事务若与创建形成数据库死锁，创建只有限重试，不吞错或部分提交；本轮未改写账号管理策略。

## 迁移与兼容

仅新增 `V8__exchange_creation.sql`，V1–V7不改写。复用V2的 `(initiator_id,idempotency_key)` 唯一约束、参与者唯一用户/物品以及item_hold物品主键。V8新增request_digest、rule_version、creation_snapshot，创建写入完整元数据；旧行保留NULL，同键访问旧无摘要记录返回409，不猜测它对应哪次请求。

`cl_exchange_demand` 保存exchange/demand、接收者在本环提供的offered_item、demand_version与当时需求快照；每个交换的需求/提供物品各唯一，非级联外键保留历史。creation_snapshot保存规范流向和原推荐解释。内部快照、私人需求说明、幂等键与摘要不对外返回，本次ExchangeView字段不变。B后续迁移从V9起协调，不需要第二张占用表。

## 请求、失败与恢复

[完整虚构样例](examples/a03-exchange-creation.json)。flows保持有向顺序，本项物品提供给下一项物品的所有者；demandId/demandVersion属于该接收者选中需求。按最小物品ID旋转，旋转起点不改变摘要，反向三环是不同请求。请求版本来自同次独立推荐，不能填0补缺。

| 场景 | HTTP / code | 结果与恢复 |
| --- | --- | --- |
| 合法2/3环；相同内容重试或旋转起点 | 200 | 同一持久ExchangeView；原ID、截止和物品版本不重复更新 |
| 相同发起人/键但不同物品、需求、版本或方向 | 409 | `幂等键已用于不同请求，请勿复用该键提交其他方案`；不返回别的交换 |
| 物品/需求/关联/用户失效、版本过期、规则选中需求变化 | 409 | `物品、需求或推荐已变化，请刷新后重新选择`；无部分创建 |
| 任何占用（包括过期）或进行中物品引用 | 409 | `物品存在交换占用或进行中的交换，不能执行此操作`；不释放已有占用 |
| 唯一/FK约束失败 | 409 | `交换创建约束冲突，整次请求已回滚，请刷新后重试` |
| 三次数据库瞬态冲突耗尽 | 409 | `交换创建遇到并发冲突，请使用同一幂等键重试` |
| 非参与者发起；伪造owner/participants等字段 | 403；400 | 身份由服务器确定，ADMIN也不能绕过 |
| 未登录/失效会话；非参与者查详情 | 401；404 | 使用原认证/私有读取边界 |
| 编辑/启停/删除进行中所选需求 | 409 | `需求正在参与交换，不能编辑、启停或删除`；需求版本保持不变 |

409时保留用户选择，刷新推荐后由用户重新决定；响应丢失或结果未知时重放同键同内容，不能自动换键重新建立交换。不同发起人的相同键独立，但仍受物品占用约束。GET推荐、详情、列表均不自动占用、过期或释放。

## 验证

2026-09-08最终执行：`node scripts/run.mjs test` 通过，用户端49项；后端52项公共规则+99项API/集成中93项通过、6项MySQL专用检查明确跳过。`CAMPUS_TEST_PORT=3333 node scripts/mysql-test.mjs` 在全新MySQL 8.4.11中151项后端检查全部通过，包含共用交换套件20项、其中6项MySQL专用测试。`CAMPUS_TEST_PORT=3333 node scripts/mysql-test.mjs --item-review-upgrade` 的1项升级保留检查通过。两个最终容器均由脚本自动停止删除。仓库交付检查、文档62处相对链接与样例JSON检查通过。新增检查复用B的 `ExchangeDomainIntegrationTest`，SQL探针只安装在测试配置，用于暂停真实事务或在真实写入后触发数据库约束；没有测试版创建服务。

- 真实POST两方/三方创建、B读取、规范化重放、不同内容/身份作用域、状态/版本/归属/有效需求拒绝、推荐GET逐行无写入。
- 在exchange、participant、demand reference、hold、item reserve五个写阶段触发真实唯一约束失败；逐行比较所有相关表，检查全回滚，并用同键成功重试。
- MySQL两连接：同物品方案仅一胜、同请求重试同ID、同键异请求拒绝、实际物品FOR UPDATE等待并拒绝已提交下架、需求修改等待创建后冻结、新/未选需求写入不能改变已锁选择。
- MySQL真实SQLSTATE 40001错误验证整事务重试及三次上限；这项是数据库错误注入，单列为重试策略验证，不冒充自然发生的死锁。
- 既有升级检查增加V7→V8：旧exchange所有字段与占用保留，新元数据NULL、不伪造需求引用；使用全新空库，禁止clean/drop。
- 测试夹具使用随机标记；清理仅自己的外键关联，比较测试前后已有业务行。MySQL使用项目脚本创建/自动删除的临时容器，不接成员日常库。

首轮检查修正了测试Java声明、物品表字段适配与SQL失败注入的异常转译；以上为修正后的完整重跑结果。既有Flyway对MySQL 8.4的支持版本提示仍存在，本次V1–V8迁移、升级与真实事务均通过，未夹带依赖升级。
