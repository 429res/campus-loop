# API 契约 v1

API根路径 `/api`，JSON UTF-8；成功 HTTP200 + `{ "code": 200, "msg": "…", "data": … }`。错误HTTP状态与code一致，data可为空；400参数错误、401未登录/会话无效、403权限不足、404不存在、409业务状态冲突（后续）、422候选规模超限、501明确待开发、500内部错误。不要把非200包裹成成功。

认证头 `Authorization: Bearer <本机登录返回令牌>`，不得写入文档样例或日志。时间以UTC ISO8601返回，前端按本地时区显示。id为数据库整数；当前规模可用JSON number，超过JS安全整数前统一迁移为字符串契约。

## 当前已实现

| 方法和路径 | 权限 | 输入/结果 |
| --- | --- | --- |
| GET /health | 公开 | 服务状态，不泄露配置 |
| POST /auth/login | 公开 | `{username,password}` → `{token,user:{id,username,displayName,role}}` |
| GET /auth/me | 登录 | 用户公开字段；每次服务端验证签名、到期、会话撤销与账号状态 |
| POST /auth/logout | 登录 | 撤销当前会话，前端清本地令牌 |
| GET /categories | 公开 | `[{id,name}]` |
| GET /items | 公开 | query `page=1&size=12&keyword=&categoryId=` → 分页 |
| GET /items/{id} | 公开 | 可见物品详情 |
| POST /items | 登录 | 发布输入 → 持久化后的物品详情 |
| POST /uploads | 登录 | multipart字段`file`，返回`{url}` |
| GET /admin/items | ADMIN | 与公开列表相同分页字段；可查看管理记录 |
| GET /admin/stats | ADMIN | `{users,items,availableItems,recommendations}` |
| GET /matches | 公开 | 2/3循环推荐数组；无副作用 |

分页统一 `{records,total,page,size}`；page从1开始，size1–100；keyword最多100字符；不要由消费端猜测records/list/rows。空结果为records空数组、total0。

发布输入：`title`、`description`、`categoryId`、`conditionLevel`（1–5）、`tags`（数组）、`wantedCategoryId`、`wantedTags`（偏好数组）、可选`imageUrl`。实际长度限制见后端DTO；两端应同步校验，服务端是最终约束。

物品输出：`id,ownerId,ownerName,title,description,categoryId,categoryName,conditionLevel,tags,wantedCategoryId,wantedCategoryName,wantedTags,imageUrl,status,createdAt`。发布者从会话确定，不接受客户端owner/role/status；本次初始化直接写AVAILABLE，后续内容审核引入PENDING_REVIEW需契约变更。

上传仅接受有效PNG/JPEG/GIF、最大5MB、最多1600万像素；后端重新编码PNG、随机文件名、验证本人拥有的上传URL后才允许发布引用。使用`/uploads/<随机文件名>.png`；禁止任意服务器路径、外链或别人的上传。初版每物品一张图，移除表单图片只移除引用，不假称服务器已删除。未引用图片清理策略属后续。

推荐：`[{id,length,score,participants:[{userId,displayName,itemId,itemTitle}],flows:[{fromUserId,fromName,toUserId,toName,itemId,itemTitle,reason}],explanation}]`。流向是提供者→接收者；category为硬条件，wantedTags仅偏好排序；每个方案的用户和物品唯一。评分60–100及去重规则见架构/后端契约。读取不创建交换或占用；最多200件AVAILABLE候选，超过422，前端显示错误而非“没有结果”。

## 后续接口设计（未实现）

| 路径草案 | 语义/并发契约 |
| --- | --- |
| GET/POST/PATCH /demands | 当前用户需求清单，owner权限，version条件更新 |
| PUT/DELETE /items/{id}/favorite | 当前用户幂等收藏，unique(user,item) |
| POST/PATCH/DELETE /admin/categories（路径待 A 确认） | C-01 最小依赖草案，尚未实现：仅 ADMIN；名称 trim 后 1–64 字符且唯一；PATCH/DELETE 必须携带服务端版本，过期版本返回409；被物品 `categoryId` 或 `wantedCategoryId` 引用时禁止级联删除并返回409。排序与可用状态尚无契约，本轮前端不提供对应写控件。 |
| POST /exchanges | 物品有序列表、规则版本、idempotencyKey；事务重校验、锁定、唯一占用；冲突409 |
| POST /exchanges/{id}/confirm | 仅参与者，state/version校验，重复确认幂等 |
| POST /exchanges/{id}/handoff | 参与者交接凭据；全部确认才转移所有权 |
| POST /exchanges/{id}/cancel | 仅允许状态下取消，原子释放属于本交换的占用 |
| GET/POST /items/{id}/history | 事件、来源、发生/记录时间、证据；提交自述不能设置管理员级别 |
| POST /reports | 目标、原因、证据，不允许恶意替别人举报 |
| POST /admin/reviews/{id}/decision | ADMIN + version + 理由 + 不可覆盖审计事件 |

后端已预留的exchange写操作返回501；其他尚未注册的路径可能404，不能把本表当成可调用功能。状态机、事务与锁定顺序见 [architecture.md](architecture.md)。字段变化先在PR中取得消费端确认，保持同一提交内服务端与两前端同步。

分类维护的响应形状仍需 A/C 在实现前确认。写入成功应返回持久化后的分类记录，前端随后从 API 回读；409 后同样回读相关记录，不以本地表单覆盖服务端。若采用停用而非删除，A 需先定义状态字段、公开 `GET /categories` 是否过滤停用项及既有物品的显示规则，D 再同步两端分类选择器。当前消费者只可依赖 `{id,name}`。

对 A 的最小依赖是：确认写路径与 DTO、补分类版本并以409区分并发冲突/重名、以409保护 `cl_item.category_id` 和 `wanted_category_id` 引用、对非 ADMIN 返回403，且写成功返回持久化结果。对 D 的当前影响为零：H5/微信继续只消费 `{id,name}`；只有 A/C 后续确认停用或排序字段时，才需要同步选择器过滤、展示顺序与失效分类回显。

精确DTO限制、已预留路径和后端实现入口见 [backend-contract.md](backend-contract.md)。
