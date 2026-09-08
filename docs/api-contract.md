# API 契约 v1

API根路径 `/api`，JSON UTF-8；成功 HTTP200 + `{ "code": 200, "msg": "…", "data": … }`。错误HTTP状态与code一致，data可为空；400参数错误、401未登录/会话无效、403权限不足、404不存在、409业务状态或版本冲突、422候选规模超限、501明确待开发、500内部错误。不要把非200包裹成成功。

认证头 `Authorization: Bearer <本机登录返回令牌>`，不得写入文档样例或日志。时间以UTC ISO8601返回，前端按本地时区显示。id为数据库整数；当前规模可用JSON number，超过JS安全整数前统一迁移为字符串契约。

## 当前已实现

| 方法和路径 | 权限 | 输入/结果 |
| --- | --- | --- |
| GET /health | 公开 | 服务状态，不泄露配置 |
| POST /auth/login | 公开 | `{username,password}` → `{token,user:{id,username,displayName,role}}` |
| GET /auth/me | 登录 | 用户公开字段；每次服务端验证签名、到期、会话撤销与账号状态 |
| PATCH /auth/me | 登录 | 仅 `{displayName}` → 从数据库回读的 `{id,username,displayName,role}` |
| POST /auth/password | 登录 | `{currentPassword,newPassword}`；成功后撤销该账号全部会话，返回 `data: null` |
| POST /auth/logout | 登录 | 撤销当前会话，前端清本地令牌 |
| GET /categories | 公开 | `[{id,name}]` |
| GET /items | 公开 | query `page=1&size=12&keyword=&categoryId=` → 分页 |
| GET /items/{id} | 公开 | 可见物品详情 |
| POST /items | 登录 | 发布输入 → 持久化后的物品详情 |
| POST /uploads | 登录 | multipart字段`file`，返回`{url}` |
| GET /admin/items | ADMIN | 与公开列表相同分页字段；可查看管理记录 |
| GET /admin/users | ADMIN | query `page=1&size=12&keyword=&role=&status=` → 账号安全字段分页 |
| PATCH /admin/users/{id}/status | ADMIN | 仅 `{status,version,reason}` → 数据库回读的账号安全字段；条件更新并审计 |
| GET /admin/users/{id}/status-audits | ADMIN | query `page=1&size=20` → 该账号启停审计分页 |
| GET /admin/stats | ADMIN | `{users,items,availableItems,recommendations}` |
| GET /matches | 公开 | 2/3循环推荐数组；无副作用 |

本人资料写入只接受 `displayName`，去除首尾空白后长度为1–64字符。用户身份、`id`、`username`、`role`、`status` 和密码哈希均由服务端会话与数据库确定；请求出现未声明字段或试图写入受保护字段返回400，且不产生部分更新。成功结果沿用登录用户公开结构，消费端可直接替换本地用户资料。

修改密码的 `currentPassword` 必填，`newPassword` 为12–64字符且UTF-8编码不超过72字节。旧密码不正确、新密码格式不正确或请求含未声明字段返回400，不修改密码也不撤销会话；未登录或会话已撤销返回401。成功时密码哈希更新与该账号全部会话删除位于同一事务，包含发起请求的当前会话及其他设备会话；消费端收到200后必须立即清除本地令牌、用户缓存并跳转登录页。登录与改密按同一用户行串行化，保证改密提交后不存在通过旧密码取得的有效会话；旧密码登录失败，新密码可重新登录。

分页统一 `{records,total,page,size}`；page从1开始，size1–100；keyword最多100字符；不要由消费端猜测records/list/rows。空结果为records空数组、total0。

管理员账号查询仅返回 `id,username,displayName,role,status,version,createdAt`，`keyword` 同时匹配用户名和显示名称，`role` 只接受 `ADMIN/USER`，`status` 只接受 `ACTIVE/DISABLED`。三个账号接口均由服务端强制校验 `ADMIN`；普通用户返回403，未登录或已撤销会话返回401。不会返回密码哈希、令牌或会话标识。

账号启停请求的 `status` 只接受 `ACTIVE/DISABLED`，`version` 是查询结果中的非负整数，`reason` 去除首尾空白后为1–500字符；出现额外字段返回400。服务端锁定账号并以版本条件更新；旧版本或并发状态变化返回409且不覆盖新状态，消费端应保留操作上下文、重新查询后再决定。状态已与目标一致且版本仍匹配时按幂等成功处理，不增加审计或版本。

当前管理员不能停用自己；最后一个可登录的 `ACTIVE` 管理员不能被停用，两者均返回409，确保系统保留管理入口。停用与登录使用同一用户行锁：登录先完成时，其会话会被随后停用事务删除；停用先完成时，登录返回401。停用状态写入、该账号全部会话撤销和审计记录位于同一事务。重新启用只改变账号状态，不重建已删除会话，用户必须重新登录。账号角色不可由这些接口修改，也不提供硬删除。

启停审计返回 `id,targetUserId,operatorUserId,operatorUsername,operatorDisplayName,previousStatus,newStatus,reason,previousVersion,newVersion,createdAt`。审计不保存口令、密码哈希、JWT、会话标识或请求头。停用不会删除或改写账号已有物品；既有公开物品仍按物品状态保持可读，但停用账号的物品不进入匹配候选，重新启用后再按物品状态参与推荐。

发布输入：`title`、`description`、`categoryId`、`conditionLevel`（1–5）、`tags`（数组）、`wantedCategoryId`、`wantedTags`（偏好数组）、可选`imageUrl`。实际长度限制见后端DTO；两端应同步校验，服务端是最终约束。

物品输出：`id,ownerId,ownerName,title,description,categoryId,categoryName,conditionLevel,tags,wantedCategoryId,wantedCategoryName,wantedTags,imageUrl,status,createdAt`。发布者从会话确定，不接受客户端owner/role/status；本次初始化直接写AVAILABLE，后续内容审核引入PENDING_REVIEW需契约变更。

上传仅接受有效PNG/JPEG/GIF、最大5MB、最多1600万像素；后端重新编码PNG、随机文件名、验证本人拥有的上传URL后才允许发布引用。使用`/uploads/<随机文件名>.png`；禁止任意服务器路径、外链或别人的上传。初版每物品一张图，移除表单图片只移除引用，不假称服务器已删除。未引用图片清理策略属后续。

推荐：`[{id,length,score,participants:[{userId,displayName,itemId,itemTitle}],flows:[{fromUserId,fromName,toUserId,toName,itemId,itemTitle,reason}],explanation}]`。流向是提供者→接收者；category为硬条件，wantedTags仅偏好排序；每个方案的用户和物品唯一。评分60–100及去重规则见架构/后端契约。读取不创建交换或占用；最多200件AVAILABLE候选，超过422，前端显示错误而非“没有结果”。

## B-01 独立需求与本人物品关联

本节为 B-01 分支的实现契约；合入并运行 V4 后才可供 D-02 调用。均要求登录，只访问本人数据，管理员也不能替其他人写需求。未知/受保护的 JSON 字段（包括 ownerId、id、createdAt、requiredTags、minimumConditionLevel）返回400。

| 方法和路径 | 输入/结果 |
| --- | --- |
| POST /demands | `{categoryId,description,preferredTags,offeredItemIds}` → Demand；初始 ACTIVE、version=0 |
| GET /demands | query `page=1&size=12&status=` → 本人非删除需求分页；status 可省略或 ACTIVE/INACTIVE |
| GET /demands/{id} | 本人非删除 Demand；外人403，不存在/已删除404 |
| PATCH /demands/{id} | `{version,categoryId?,description?,preferredTags?,offeredItemIds?}` → 更新后的 Demand；至少一个可编辑字段；省略保持原值，显式null拒绝 |
| PATCH /demands/{id}/status | `{version,status}`，status 仅 ACTIVE/INACTIVE → Demand；相同状态也必须版本一致且 version+1 |
| DELETE /demands/{id}?version=0 | 逻辑删除 → `{id,status:"DELETED",version}`；不支持恢复；再次操作404 |
| GET /demands/offerable-items | query `page=1&size=12` → 本人 AVAILABLE 且无 cl_item_hold 的物品分页，字段见下 |

分页沿用 `{records,total,page,size}`，page≥1、size为1–100。需求按 createdAt/id 降序；关联按 itemId 升序返回。

Demand 字段：`id,ownerId,categoryId,categoryName,description,preferredTags,status,version,createdAt,updatedAt,offeredItems`。description 为纯文本可空串、最长2000；categoryId 为已存在正整数分类；preferredTags 必填数组，最多8项，每项非空白且最多20字符，服务端 trim、转小写、去重。`offeredItemIds` 必填数组，允许空，最多100个不同正整数。

`offeredItems` 及 offerable-items 的 records 字段：`itemId,title,categoryId,conditionLevel,status,offerable`，全部来自现有 cl_item 实时读取；offerable 同时校验仍属需求本人、AVAILABLE、无占用。该返回不构成未来可交换承诺。归属已转走的历史关联只返回 itemId、offerable=false，其他字段为null，避免继续披露他人物品的非公开状态。

编辑、状态切换、删除均要求当前非负整数 version；过期409，不覆盖任何字段或关联，成功 version+1，updatedAt 为UTC ISO8601。编辑仅更新提交字段，offeredItemIds 若提供则全量替换且和需求在同一事务内提交。切换 ACTIVE 时重新验证全部关联；INACTIVE 可以保留已失效的历史关联。字段编辑不重新保存未提交的关联，D 可用 offeredItemIds=[] 清空。

关联是多对多的候选集合：一条需求0–100件本人 AVAILABLE 且无占用的物品，同一物品允许出现在本人多条需求中；关联不复制物品、不更改 owner、不占用。重复ID或不存在物品400，外人物品403，不可提供状态/占用409。非法分类400；未登录401。被删除需求的读写404；其他越权403。并发更新按需求行锁与 version 条件串行，关联写入按物品ID升序锁定再校验。

停用保留详情与关联并可恢复 ACTIVE，`GET /demands?status=ACTIVE` 不包含 INACTIVE。删除置 DELETED 并保留原需求内容、关联和ID作为墓碑，普通读写不可再访问，不提供物理删除接口；关联外键禁止物理删除被引用的需求/物品，后续持久化消费者必须采用同样的非级联外键，历史引用不会被接口删除破坏。正式交换引用下的额外编辑/停用限制由 B-03 与 A 协商，本轮没有此写入能力。

旧字段策略：cl_item.wantedCategoryId/wantedTags 仍是旧发布与 GET /matches 的唯一来源；独立需求是上述 CRUD 的唯一来源。不回填、不双写、不自动同步或关闭旧字段，B-01 的停用/删除只影响独立需求，不改变旧推荐结果。B-02的显式独立入口、需求选择与规则版本见下节；本轮不退出旧链路，消费确认仍单独跟踪。requiredTags/最低成色既不接收也不隐式启用。

需求迁移使用 V4，保留 main 已合入的 V3 用户状态管理迁移及 V1/V2 原文。A/D 的候选基数复核仍待团队回复；不把未收到的确认写成已完成。接入样例和状态见 [b01-independent-demands.md](b01-independent-demands.md)。

## B-02 独立需求推荐（本功能分支）

B-01已合入main，当前迁移为V4；本节为B-02可评审实现契约，不代表已合入main或完成D-02页面。兼容和多需求策略已在 [Issue #7](https://github.com/429res/campus-loop/issues/7) 同步，消费端复核状态见 [B-02说明](b02-independent-matching.md)。

| 方法和路径 | 权限与结果 |
| --- | --- |
| GET /matches | 旧公开入口；仍只使用物品wanted字段，返回原推荐数组。可省略ruleVersion或传legacy-v1，其他值400 |
| GET /matches/independent | 登录；仅返回含当前用户的2/3人环。可省略ruleVersion或传independent-v2，其他值400；成功data为`{ruleVersion:"independent-v2",recommendations:[...]}` |

独立入口仅接受单个ruleVersion查询参数，其他参数（包括ownerId、requiredTags、minimumConditionLevel）或重复参数400；不接受客户端身份、不混入或回退旧wanted字段；未登录/无效会话401。无需求、无候选关联或无闭环返回200及`{ruleVersion:"independent-v2",recommendations:[]}`，不能通过尝试其他版本伪造成功。旧入口作为legacy-v1继续用于现有演示，空数组语义不变，独立需求启停只影响新入口。

每个新推荐包含 `id,ruleVersion,length,score,participants,flows,explanation`。participants沿用旧字段；flows保留fromUserId/fromName/toUserId/toName/itemId/itemTitle/reason，并新增 `demandId,matchedDemandIds,matchedCategoryId,matchedCategoryName,matchedTags`。demandId是接收者被选中的独立需求；matchedDemandIds包含同一流向全部分类命中的有效需求ID（含选中，升序）；matchedTags只展示被选中需求与提供物品的标签交集。不会返回私人需求description或未命中的偏好标签，也不返回不含当前用户的环。

有效边A→B：A物品分类满足B的某条ACTIVE需求，且该需求关联B在本环提供的那件物品。物品AVAILABLE、用户ACTIVE、无cl_item_hold行、关联物品当前owner与需求owner一致；INACTIVE/DELETED/无关联不参与。每环2/3人，每人一件，用户/物品唯一，失效关联不会让新owner继承旧需求。

同流向多需求不展开成多个物品环：按`min(标签交集数,4)`降序、需求ID升序选择一条用于计分与理由，其他命中ID只作解释。不得把多需求标签并集加分；有不同物品选择时仍可形成不同方案。评分继续`60 + round(10 × 各流向计分标签数之和 / 环长)`，60–100；先分数降序、环长升序、规范ID字典序。新ID形如`independent-v2:cycle-1-2-3`，环旋转至最小物品ID，保留方向，反向三环是另一方案。ID及version是规则结果标识，不能作为物品预约或交换创建凭据。

新旧入口统一排除存在占用行的物品。候选保留200件上限：先检查全体AVAILABLE+有效用户+无占用物品，再筛独立需求；超出422。独立入口额外设显式资源保护：最多20000条有效需求关联、1000个推荐、全响应flows的matchedDemandIds累计最多20000项；任何一项超出均整次422，不返回截断或部分结果。D应显示服务端msg并提示缩小候选/等待后续分区，不能把422展示为无结果。

只读多表快照使用REPEATABLE_READ，无FOR UPDATE、交换创建、状态更新或占用。分类仍为唯一硬需求条件，标签仅排序，requiredTags/最低成色未启用，也没有新增数据库字段。两方、三方、无结果及超限虚构样例见[B-02说明](b02-independent-matching.md)。

## 后续接口设计（未实现）

| 路径草案 | 语义/并发契约 |
| --- | --- |
| PUT/DELETE /items/{id}/favorite | 当前用户幂等收藏，unique(user,item) |
| POST /exchanges | 物品有序列表、规则版本、idempotencyKey；事务重校验、锁定、唯一占用；冲突409 |
| POST /exchanges/{id}/confirm | 仅参与者，state/version校验，重复确认幂等 |
| POST /exchanges/{id}/handoff | 参与者交接凭据；全部确认才转移所有权 |
| POST /exchanges/{id}/cancel | 仅允许状态下取消，原子释放属于本交换的占用 |
| GET/POST /items/{id}/history | 事件、来源、发生/记录时间、证据；提交自述不能设置管理员级别 |
| POST /reports | 目标、原因、证据，不允许恶意替别人举报 |
| POST /admin/reviews/{id}/decision | ADMIN + version + 理由 + 不可覆盖审计事件 |

后端已预留的exchange写操作返回501；其他尚未注册的路径可能404，不能把本表当成可调用功能。状态机、事务与锁定顺序见 [architecture.md](architecture.md)。字段变化先在PR中取得消费端确认，保持同一提交内服务端与两前端同步。

精确DTO限制、已预留路径和后端实现入口见 [backend-contract.md](backend-contract.md)。
