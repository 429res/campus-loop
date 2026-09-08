# API 契约 v1

API根路径 `/api`，JSON UTF-8；成功 HTTP200 + `{ "code": 200, "msg": "…", "data": … }`。错误HTTP状态与code一致，data可为空；400参数错误、401未登录/会话无效、403权限不足、404不存在、409业务状态冲突（后续）、422候选规模超限、501明确待开发、500内部错误。不要把非200包裹成成功。

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

## 后续接口设计（未实现）

| 路径草案 | 语义/并发契约 |
| --- | --- |
| GET/POST/PATCH /demands | 当前用户需求清单，owner权限，version条件更新 |
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
