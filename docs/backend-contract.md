# 后端实现与后续事务契约

完整对外 API 以 [api-contract.md](api-contract.md) 为入口。本文说明已实现的关键行为与尚未实现的交换写入设计。

## 已实现范围

- 账号密码登录、读取当前用户、服务端注销、ADMIN / USER 两级授权。
- 默认关闭的注册准入；仅显式 `DEVELOPMENT_SELF_SERVICE` 模式接受 `username/password/displayName`，事务写入固定 `USER/ACTIVE` 与 BCrypt 哈希。成功不自动创建会话，用户名唯一约束处理并发重名。
- 本人仅修改 `displayName` 并从数据库回读；修改密码须验证旧密码，成功后原子撤销该账号全部会话。登录与改密锁定同一用户行，改密提交后旧密码不能留下有效会话。
- 管理员按关键词、角色和状态分页查询账号，以 `version` 条件启停并记录安全审计；停用与全部会话撤销原子提交，且登录与停用锁定同一用户行。自我停用和失去最后一个可用管理员的操作返回409。
- 分类读取，分页搜索物品、物品详情，登录发布，管理员查看全部物品与统计。
- 图片上传及发布时归属校验。支持 PNG / JPEG / GIF，5 MB、1600 万像素上限；解码重存 PNG，GIF 仅保留首帧。返回相对 `/uploads/<UUID>.png`，所有端须以 API 服务地址解析。图片链接是公开展示资源，不应上传私密内容。
- 双方、三方推荐读取；所有演示来自数据库的虚构数据。
- `/api/health` 查询数据库连接；不暴露连接串或凭据。
- 发布在初始版本直接成为 `AVAILABLE`，用于完整示范链路。正式内容审核接入前必须将发布默认状态迁移为 `PENDING_REVIEW` 并补充审核 API；当前页面不得冒充已有审核流程。

普通查询仅显示 AVAILABLE、RESERVED、EXCHANGED。分页页码从 1 开始，size 1–100。文本使用纯文本渲染，标题 100、描述 2000 字符；分类必须存在，成色 1–5；两组标签各最多 8 个，每个 20 字符。时间字段按 UTC 的 ISO 格式输出，前端按本地时区显示。

每件物品当前绑定一个想要分类与一组偏好标签，作为“我有什么 / 想要什么”最小样例。B-01 新增独立需求 CRUD 与本人物品候选关联，接口和兼容边界见 [api-contract.md](api-contract.md) 与 [b01-independent-demands.md](b01-independent-demands.md)。独立需求接入匹配、收藏和更多个人资料字段尚未实现；本人资料编辑已由 A-01 提供。

## 有向匹配

独立纯 Java 算法位于 `sys-project-com/.../matching/CycleMatcher.java`，无 Spring、数据库、外部 AI 依赖。

方向 `A → B` 表示 **A 的物品给 B**。必须满足：

1. 环长恰为 2 或 3；不同参与者，每人提供一件物品；物品 ID 不重复。
2. 所有物品均 `AVAILABLE`，候选读取排除 DISABLED 用户。
3. 每条流向中，提供物品的分类等于接收方 `wantedCategoryId`。

`wantedTags` 是软偏好，用于解释和排序，不会否决分类匹配。成色第一版仅展示，不参与门槛或排序。标签统一 trim、小写、去重。分数为 `60 + round(10 × Σ min(4, 每条流向共同偏好标签数) / 环长)`，范围 60–100；这是规则分数，不是成功概率。先分数降序，再环长升序，再推荐 ID 稳定排序。

环从最小物品 ID 起记录，旋转视为同一推荐；方向相反的三方环代表不同物品流向，分别保留。重复输入 ID 去重，冲突的同 ID 记录全部排除。输出每个参与者、每条物品流向、满足的分类、重合标签和总体说明。

HTTP GET `/api/matches` 只读即时计算，不持久化推荐，不创建交换，不写 `cl_item_hold`。推荐 ID 是当次规则生成的环标识，不能作为预约凭证。当前最多支持 200 件 AVAILABLE 候选和 1000 条推荐；任一超出返回 422，在构建第 1001 条解释对象前中止，避免隐藏截断或密集结果耗尽内存。`GET /api/admin/stats` 在该规模限制下保留基础计数，以 `recommendations=null` 和 `recommendationsStatus=LIMIT_EXCEEDED` 表示推荐数量不可用。下一阶段可按学校/分类建图、预计算邻接边和分页结果。

## 已建立的数据结构，写入待开发

`cl_exchange`：发起人、状态、version、请求幂等键、过期时间。`cl_exchange_participant`：每人提供物品和接收人、确认/交出/收到时间；同一交换内用户与物品各唯一。`cl_item_hold`：item_id 为主键，一个物品只能有一条活动占用。`cl_item_history`：物品与可选交换、事件类型、来源用户、对方确认者、管理员核验者、发生与记录时间。

这是下一阶段的表结构基础，不代表已经具备业务接口。当前 POST `/api/exchanges` 与 confirm/cancel/handoff 明确 HTTP 501。履历、争议、举报、审核不得以伪成功 API 替代。

## 交换创建与并发设计（后续实现）

建议 POST `/api/exchanges` 请求含 `{ itemIds: [..], idempotencyKey }`，由服务器重新计算和验证环方向，不能相信客户端 score、用户 ID、reason 或状态。发起人必须拥有其中一件物品。

一个数据库事务中：

1. 以确定的物品 ID 升序执行 `SELECT ... FOR UPDATE`，重新读取所有拥有者、分类、需求、状态、version，并检查参与者和环规则。
2. 读取幂等键；相同发起人 + 幂等键的相同请求返回原交换，不同 payload 返回 409。需新增规范化请求摘要字段防止幂等键被误复用。
3. 创建 AWAITING_CONFIRMATION 交换及参与者。创建时即插入短时 `cl_item_hold`、将物品标为 RESERVED；“正式交换创建”会占用，推荐浏览不会。唯一 item_id 是跨不同交换并发的最后保障；冲突整笔回滚并返回 409。
4. 记录 expires_at 和 version。事务提交后才通知；下一阶段使用事务 outbox，通知失败不回滚已确认交易。

确认接口只允许该交换参与者。每次事务先锁 exchange，再按同一顺序锁 items，校验到期时间及当前状态；重复确认幂等。全部确认才变 READY。确认截止时间与交接截止时间区分设置，建议在下一迁移增加明确字段。

READY 时每名参与者分别记录 handedOffAt / receivedAt，所有交接双方均确认后才 COMPLETED，更新物品 EXCHANGED，并追加 BOTH_CONFIRMED 履历。禁止一个人的点击冒充所有人的确认。完成事务把物品 owner 转给对应接收人、关闭原挂牌需求，同时保留原物品永久 ID 与包含原始参与者的所有权事件。重新交换须由新拥有者重新填写需求；具体关闭字段及历史快照在后续版本迁移中补全，不能只覆盖 owner 而丢失来源。

取消、超时任务、确认都锁定同一 exchange 并以 version 或条件更新串行化；定时任务可用 `FOR UPDATE SKIP LOCKED` 分批领任务。释放占用必须 `DELETE ... WHERE item_id=? AND exchange_id=?`，防止旧任务释放另一笔交换。过期判定由数据库 UTC 时间统一；处理完成一次即可，重复任务无副作用。

## 状态与履历可信度

| 当前状态 | 合法下一状态（计划） | 条件 |
| --- | --- | --- |
| AWAITING_CONFIRMATION | READY | 全体参与者确认且未过期 |
| AWAITING_CONFIRMATION | CANCELLED / EXPIRED | 撤回、拒绝或确认截止到期，释放本交换占用 |
| READY | COMPLETED | 每条交接由双方完成确认 |
| READY | CANCELLED / EXPIRED / DISPUTED | 按协商取消、交接超时或发起争议规则处理；已发生交接时不能直接释放并恢复上架 |
| DISPUTED | COMPLETED / CANCELLED | 管理员基于证据裁定，保留审计 |
| COMPLETED / CANCELLED / EXPIRED | 无 | 终态不可复活；补充证据使用新事件 |

履历等级 SELF_REPORTED = 用户自述；BOTH_CONFIRMED = 有关联对方明确确认；ADMIN_VERIFIED = 管理员核验证据。等级不得由普通客户端自行填写或升高。记录来源与 occurredAt / recordedAt / confirmedAt / verifiedAt；事件保留原文，不覆盖旧证据。维修事件默认自述；对方确认只表明确认该记录，不能自动证明专业维修质量。

## 身份、部署与未完成能力

受保护接口使用 `Authorization: Bearer <token>`。JWT 8 小时到期，MySQL 会话记录同时校验；注销删除当前会话。修改密码和管理员停用账号均在同一事务删除该账号所有会话，禁用后拒绝登录，重新启用不会恢复旧令牌。管理员重置密码尚未实现。

当前运行基础面向四人本地开发。开发自助注册默认关闭，显式开启也不代表学校身份核验。公开部署前须确定真实注册准入，并补充登录限速、找回密码、学校身份策略、细粒度权限、反垃圾、上传生命周期清理、审计和运维指标；这些是后续范围，不是假装已完成的入口。数据库与 JWT 密钥仅由后端加载，前端不能持有这些凭据。
