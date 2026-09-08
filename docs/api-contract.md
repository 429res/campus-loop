# API 契约 v1

API根路径 `/api`，JSON UTF-8；成功 HTTP200 + `{ "code": 200, "msg": "…", "data": … }`。错误HTTP状态与code一致，data可为空；400参数错误、401未登录/会话无效、403权限不足、404不存在、409业务状态或版本冲突、422候选或推荐规模超限、429请求频率超限、501明确待开发、500内部错误。不要把非200包裹成成功。

认证头 `Authorization: Bearer <本机登录返回令牌>`，不得写入文档样例或日志。时间以UTC ISO8601返回，前端按本地时区显示。id为数据库整数；当前规模可用JSON number，超过JS安全整数前统一迁移为字符串契约。

## 当前已实现

| 方法和路径 | 权限 | 输入/结果 |
| --- | --- | --- |
| GET /health | 公开 | 服务状态，不泄露配置 |
| POST /auth/register | 公开（受运行时准入开关约束） | `{username,password,displayName}` → `{id,username,displayName,role}`；不自动登录 |
| POST /auth/login | 公开 | `{username,password}` → `{token,user:{id,username,displayName,role}}` |
| GET /auth/me | 登录 | 用户公开字段；每次服务端验证签名、到期、会话撤销与账号状态 |
| PATCH /auth/me | 登录 | 仅 `{displayName}` → 从数据库回读的 `{id,username,displayName,role}` |
| POST /auth/password | 登录 | `{currentPassword,newPassword}`；成功后撤销该账号全部会话，返回 `data: null` |
| POST /auth/logout | 登录 | 撤销当前会话，前端清本地令牌 |
| GET /categories | 公开 | 默认 ACTIVE 数组；includeInactive=true 含停用，字段 id/name/status/sortOrder/version |
| GET /admin/categories | ADMIN | query page=1&size=12&keyword=&status= → 全部分类分页 |
| GET /admin/categories/{id} | ADMIN | 单条分类回读 |
| POST /admin/categories | ADMIN | `{name,sortOrder?}` → ACTIVE 分类 |
| PATCH /admin/categories/{id} | ADMIN | `{version,name?,sortOrder?,status?}` → 数据库回读 |
| DELETE /admin/categories/{id}?version=0 | ADMIN | 仅未引用分类可删除；成功 data=null |
| GET /items | 公开 | query `page=1&size=12&keyword=&categoryId=` → 分页 |
| GET /items/{id} | 公开 | 可见物品详情 |
| POST /items | 登录 | 严格发布输入 → PENDING_REVIEW 本人详情 |
| GET /items/mine | 登录 | 本人全部现有状态分页；query `page=1&size=12&keyword=&categoryId=&status=` |
| GET /items/mine/{id} | 登录 | 本人详情，含下架等非公开状态；用于表单及冲突回读 |
| PUT /items/{id} | 登录且本人 | `version` + 完整发布表单 → 数据库回读的物品；未占用 AVAILABLE/PENDING_REVIEW/REJECTED 编辑后待审 |
| POST /items/{id}/withdraw | 登录且本人 | 仅 `{version}` → 数据库回读的 HIDDEN 物品；仅未占用 AVAILABLE |
| POST /uploads | 登录 | multipart字段`file`，返回`{url}` |
| PUT /items/{id}/favorite | 登录 | 无 body 或 `{}` → `{itemId,favorited:true}`；目标须公开可见，重复幂等 |
| DELETE /items/{id}/favorite | 登录 | 无 body 或 `{}` → `{itemId,favorited:false}`；重复取消、目标不可见/不存在也稳定成功 |
| GET /favorites | 登录 | query `page=1&size=12` → 当前会话本人收藏分页，含不可见占位记录 |
| GET /admin/items | ADMIN | 原分页字段增加 status；全部七种状态可查 |
| GET /admin/items/{id} | ADMIN | 含非公开物品及最近审核信息 |
| POST /admin/items/{id}/review | ADMIN | `{version,decision,reason}` → 审核后回读，详见第四批 |
| GET /admin/items/{id}/review-audits | ADMIN | 审计分页，含内容前后快照 |
| GET /admin/users | ADMIN | query `page=1&size=12&keyword=&role=&status=` → 账号安全字段分页 |
| PATCH /admin/users/{id}/status | ADMIN | 仅 `{status,version,reason}` → 数据库回读的账号安全字段；条件更新并审计 |
| GET /admin/users/{id}/status-audits | ADMIN | query `page=1&size=20` → 该账号启停审计分页 |
| GET /admin/stats | ADMIN | `{users,items,availableItems,recommendations,recommendationsStatus}`；推荐计数规则见下文 |
| GET /matches | 公开 | 2/3循环推荐数组；无副作用 |

认证与高成本上传写接口使用可配置单实例固定窗口限流：登录10次/分钟/来源地址，开发注册5次/10分钟/来源地址，改密5次/10分钟/用户，公开和私有证据上传合计20次/分钟/用户。所有处理结果均计数；超限返回HTTP/code 429、统一`msg`和`data:null`，`Retry-After`给出向上取整的恢复秒数，`X-RateLimit-Reset`给出UTC ISO-8601窗口结束时间。窗口结束自动恢复，不改变账号、口令或会话。匿名主体默认只使用TCP直连地址并忽略客户端转发头；只有部署配置明确列入的直连代理才解析从右向左的`X-Forwarded-For`。配置、C/D恢复行为及单/多实例边界见[A-05限流说明](a05-auth-upload-rate-limit.md)。

本人资料写入只接受 `displayName`，去除首尾空白后长度为1–64字符。用户身份、`id`、`username`、`role`、`status` 和密码哈希均由服务端会话与数据库确定；请求出现未声明字段或试图写入受保护字段返回400，且不产生部分更新。成功结果沿用登录用户公开结构，消费端可直接替换本地用户资料。

修改密码的 `currentPassword` 必填，`newPassword` 为12–64字符且UTF-8编码不超过72字节。旧密码不正确、新密码格式不正确或请求含未声明字段返回400，不修改密码也不撤销会话；未登录或会话已撤销返回401。成功时密码哈希更新与该账号全部会话删除位于同一事务，包含发起请求的当前会话及其他设备会话；消费端收到200后必须立即清除本地令牌、用户缓存并跳转登录页。登录与改密按同一用户行串行化，保证改密提交后不存在通过旧密码取得的有效会话；旧密码登录失败，新密码可重新登录。

注册准入默认 `CLOSED`，此时合法注册请求返回403且不写入用户。仅本地开发或隔离联调可显式配置 `CAMPUS_REGISTRATION_MODE=DEVELOPMENT_SELF_SERVICE`；该模式只是开发自助注册，不代表校园身份已核验，不能用于公开部署。注册请求只接受 `username,password,displayName`：用户名 trim 后为3–64位且仅含字母、数字、下划线、点或连字符；密码为12–64字符且UTF-8编码不超过72字节；显示名称 trim 后为1–64字符。服务端固定写入 `USER/ACTIVE`，拒绝 `id/role/status/passwordHash/version` 及其他未声明字段。成功返回公开用户结构但不创建会话、不返回密码、哈希或 token，消费端随后使用现有登录接口；重名返回409，校验错误返回400，关闭准入返回403。并发相同用户名依靠数据库唯一约束保证仅一条用户记录成功，失败请求不留下半成品账号。

分页统一 `{records,total,page,size}`；page从1开始，size1–100；keyword最多100字符；不要由消费端猜测records/list/rows。空结果为records空数组、total0。

管理员账号查询仅返回 `id,username,displayName,role,status,version,createdAt`，`keyword` 同时匹配用户名和显示名称，`role` 只接受 `ADMIN/USER`，`status` 只接受 `ACTIVE/DISABLED`。三个账号接口均由服务端强制校验 `ADMIN`；普通用户返回403，未登录或已撤销会话返回401。不会返回密码哈希、令牌或会话标识。

账号启停请求的 `status` 只接受 `ACTIVE/DISABLED`，`version` 是查询结果中的非负整数，`reason` 去除首尾空白后为1–500字符；出现额外字段返回400。服务端锁定账号并以版本条件更新；旧版本或并发状态变化返回409且不覆盖新状态，消费端应保留操作上下文、重新查询后再决定。状态已与目标一致且版本仍匹配时按幂等成功处理，不增加审计或版本。

当前管理员不能停用自己；最后一个可登录的 `ACTIVE` 管理员不能被停用，两者均返回409，确保系统保留管理入口。停用与登录使用同一用户行锁：登录先完成时，其会话会被随后停用事务删除；停用先完成时，登录返回401。停用状态写入、该账号全部会话撤销和审计记录位于同一事务。重新启用只改变账号状态，不重建已删除会话，用户必须重新登录。账号角色不可由这些接口修改，也不提供硬删除。

启停审计返回 `id,targetUserId,operatorUserId,operatorUsername,operatorDisplayName,previousStatus,newStatus,reason,previousVersion,newVersion,createdAt`。审计不保存口令、密码哈希、JWT、会话标识或请求头。停用不会删除或改写账号已有物品；既有公开物品仍按物品状态保持可读，但停用账号的物品不进入匹配候选，重新启用后再按物品状态参与推荐。

管理端账号页面在启停成功后使用原筛选和分页重新查询列表，并重新读取已打开账号的审计；409 时保留理由和操作上下文、回读最新账号版本后要求人工重新判断，不自动重试。403 不清空已有页面；401 统一清除本地会话并返回登录页。前端角色入口仅改善体验，不替代上述服务端鉴权。

发布输入：`title`、`description`、`categoryId`、`conditionLevel`（1–5）、`tags`（数组）、`wantedCategoryId`、`wantedTags`（偏好数组）、可选`imageUrl`。实际长度限制见后端DTO；两端应同步校验，服务端是最终约束。

物品输出：`id,ownerId,ownerName,title,description,categoryId,categoryName,conditionLevel,tags,wantedCategoryId,wantedCategoryName,wantedTags,imageUrl,status,version,createdAt`。version 是非负整数；原发布、公开列表/详情及管理列表仅新增该字段，其余结构和可见性兼容。发布者从会话确定，不接受客户端owner/role/status；新发布写 PENDING_REVIEW，审核与旧数据策略见下方第四批契约。

### A-02 第一批：本人管理与 D 表单契约

本人列表、本人详情、编辑和下架的身份均来自有效会话；管理员也不能借此管理他人物品。本人列表在数据库以 owner_id 限定记录与 total，额外 ownerId 查询参数不改变会话身份；默认包含 `DRAFT/PENDING_REVIEW/REJECTED/AVAILABLE/RESERVED/EXCHANGED/HIDDEN`，status 可省略、空串或上述单个值，其他值400。按 createdAt/id 降序，keyword 最长100，categoryId 若提供须为正整数，page≥1、size1–100。本人详情无公开状态过滤，非本人403，不存在404；未登录、撤销或失效会话401。

编辑为完整 `PUT`：只接受 `version,title,description,categoryId,conditionLevel,tags,wantedCategoryId,wantedTags,imageUrl`。除 imageUrl 外全部必填，不是部分 PATCH。字段复用发布 DTO：title 非空白且最多100字符，description 非空白且最多2000，分类须为 ACTIVE 的正整数，conditionLevel 为整数1–5，两组标签为数组、各最多8个非空白且最长20字符的字符串。标题/描述 trim，标签 trim/小写/去重。旧 wanted 字段仍按旧发布语义编辑，不同步独立需求。imageUrl 最长255，省略、null或空白移除引用；非空必须为本人上传 URL，任意外链、服务器路径及他人上传400。表单保留原图必须原样带回 imageUrl，移除引用不删除上传文件。

version 必须是 JSON 非负整数，不接受字符串或小数。未声明字段（包括 id/ownerId/owner/role/status/createdAt/fields）和类型错误400，整次不写入。下架只接受 `{version}`，不接受客户端目标 status。编辑允许 `AVAILABLE/PENDING_REVIEW/REJECTED` 并提交待审，下架仍仅允许 `AVAILABLE`；两者均要求不存在任何 cl_item_hold 行（包括过期行）、没有 `AWAITING_CONFIRMATION/READY/DISPUTED` 交换的参与者引用；其他状态或占用409。下架置 `HIDDEN`，保留ID、归属、正文、图片引用、需求关联、履历和交换记录；不提供硬删除或重新上架。HIDDEN 当前只允许本人读取，不允许编辑或重复下架。

编辑/下架在事务内锁定物品，复核归属、状态、占用与 version，按 id/owner/status/version 条件更新并将 version 加1，再从数据库回读。旧版本、并发编辑/下架和版本上限均409，不覆盖已提交数据；相同字段的有效编辑也加1。D 应保存版本与完整本人表单；409保留当前用户输入，GET /items/mine/{id} 回读并提示重新判断，不自动使用新版本覆盖。400显示msg并保留输入，401按会话归属恢复，403/404停止提交并回到本人列表。具体状态矩阵、示例与验证见 [A-02 接入说明](a02-own-items.md)，协作已同步 [Issue #15](https://github.com/429res/campus-loop/issues/15)，D 消费确认与页面联调仍待完成。

上传仅接受有效PNG/JPEG/GIF、最大5MB、最多1600万像素；后端重新编码PNG、随机文件名、验证本人拥有的上传URL后才允许发布引用。使用`/uploads/<随机文件名>.png`；禁止任意服务器路径、外链或别人的上传。初版每物品一张图，移除表单图片只移除引用，不假称服务器已删除。未引用图片清理策略属后续。

推荐：`[{id,length,score,participants:[{userId,displayName,itemId,itemTitle}],flows:[{fromUserId,fromName,toUserId,toName,itemId,itemTitle,reason}],explanation}]`。流向是提供者→接收者；category为硬条件，wantedTags仅偏好排序；每个方案的用户和物品唯一。评分60–100及去重规则见架构/后端契约。读取不创建交换或占用；最多200件AVAILABLE候选、1000条推荐，任一超限返回422，前端显示错误而非“没有结果”；不返回截断的部分结果。

管理概览统计当前无 query 参数、时间范围或 `asOf`，各字段是顺序查询的当前全量读数，不承诺同一时点强一致快照。`users` 是 `cl_user` 全部账号行数，包含 ADMIN/USER 和 ACTIVE/DISABLED；`items` 是 `cl_item` 全状态行数；`availableItems` 只按 `status=AVAILABLE` 计数，不代表排除停用 owner、占用后的精确匹配候选。三者单位分别为 user id、item id、item id；空集合返回0。

`recommendations` 是与公开 legacy-v1 `GET /matches` 同源的当前 2/3 人候选环方案数，按规范化物品 ID 环去重；它不是 independent-v2 推荐数、持久交换数、参与者数或完成交换数，读取不写占用或交换。正常时 `recommendationsStatus="AVAILABLE"`、`recommendations` 为非负整数（无方案为0）。仅候选或方案规模超限时，`GET /admin/stats` 仍返回200及三项基础计数，`recommendationsStatus="LIMIT_EXCEEDED"`、`recommendations=null`；前端显示不可用及超限原因，不得显示为0。其他数据库/API错误走非200错误链路，消费端显示读取失败，不得转换为0。

当前看板另以 `GET /admin/items?page=1&size=1&status=PENDING_REVIEW` 的服务端 `PageResult.total` 显示待审核物品量；这是独立请求和当前状态计数，不从 `records` 当前页推算，也不与 `/admin/stats` 组成原子快照。生产看板没有日期筛选或趋势数据。完成交换、交换状态分布及期间统计尚无管理员接口，均为待开发，不得由推荐数、参与者私有 `/exchanges/mine` 或分页当前页替代。若后续增加期间完成量，按唯一 exchange id 去重，以 UTC `cl_exchange_event.new_status=COMPLETED` 的 `occurred_at` 使用左闭右开 `[from,to)`；旧 COMPLETED 记录缺少该事件时必须明确排除/时间不可知，不得用 `created_at` 猜测。

## A-02 第三批：管理员分类维护

本功能分支实现以下契约；运行新增 V6 后可用，保留 V1–V5。实现前已在 [Issue #20](https://github.com/429res/campus-loop/issues/20) 同步 C/D/B，消费端确认仍待回复。

Category 为 `{id,name,status,sortOrder,version}`，没有父级或无限层级。name trim 后1–64字符、非空白，大小写归一后唯一（其余等价性遵循数据库排序规则），停用也保留名称唯一性；内部 name_key 不返回。sortOrder 为整数0–9999，越小越靠前，相同时 id 升序。status 仅 ACTIVE/INACTIVE；version 为非负整数。现有分类迁移为 ACTIVE/sortOrder=0/version=0，id/name 不变。

公开 GET /categories 保持数组，默认仅 ACTIVE；includeInactive=true 返回全部用于历史浏览。旧客户端仍可读 id/name 并按返回顺序展示，停用分类不再出现在默认选择器；历史物品/需求的 categoryName 始终按当前分类名称解释，不因停用变空。名称编辑会同步影响历史记录的当前显示名称，不保存分类名称快照。

管理分页默认包含全部状态，status 可省略/空串/ACTIVE/INACTIVE，keyword 最长100且按名称搜索；page≥1、size1–100，排序同公开数组，total 为过滤后的分类数，空结果 records=[]/total=0，越界页 records=[]但保留总数。所有 /admin/categories 路径复用会话 ADMIN 校验，未登录401、普通用户403。

POST 只允许 name、可选 sortOrder（默认0），服务端固定 ACTIVE/version=0。PATCH 只允许 version 与 name/sortOrder/status，至少一个可编辑字段，省略保持原值、显式null或未知字段400；整数不接受字符串、小数。任意有效 PATCH（含相同值）version+1；旧版本409且不覆盖，版本上限409。POST/PATCH 成功返回数据库回读记录；同名或并发重名409，错误 msg 分别说明重名、版本或引用冲突。

DELETE 必须携带 query version；缺少/负数/非整数400，分类不存在404、旧版本409。仅未被任何物品 category_id/wanted_category_id 或独立需求 category_id 引用的分类允许物理删除，成功200/data=null；引用中409，包含 HIDDEN 等历史物品和 INACTIVE/DELETED 需求墓碑。即使已停用仍受保护。既有外键不级联，禁止清除引用来绕过保护；再次删除404。

停用保留历史内容与引用，可经 PATCH 恢复 ACTIVE。新发布、完整物品编辑、需求新增/字段编辑及切换 ACTIVE 都在事务内要求最终选择的分类 ACTIVE；不存在或停用400，整次回滚，包括版本与候选关联。分类停用后，需求可改选 ACTIVE 分类再保存，也仍可停用/逻辑删除；物品下架遵循原状态机，不要求分类可用。分类停用只限制后续选择与上述写入，不修改现有物品/需求状态，不召回已有推荐，不修改交换状态机或匹配算法。

写入先锁已有物品（需求操作先锁所属用户、再锁需求）及占用，再按分类ID升序锁定所需分类；分类维护只锁分类、引用检查不反向锁物品/需求，READ_COMMITTED 与外键共同防止并发悬空引用。停用/删除先提交时，新业务拒绝；业务先提交时，删除看到引用并409。完整字段样例、C/D/B影响及验证见 [a02-category-maintenance.md](a02-category-maintenance.md)。

## A-02 第二批：收藏与 D-02 消费契约

三个收藏接口只操作服务端会话对应的 user_id；管理员也只能访问本人收藏，没有按用户ID查询、他人收藏列表或收藏者计数接口。写接口不接受查询参数，只允许无 body 或空 JSON 对象 `{}`；携带 userId/ownerId/itemId/status 等任意请求字段或查询参数400。路径 id 须为正整数；未登录/会话失效401，非法ID或分页400。GET 仅接受 page、size 各一次（默认1/12，page≥1、size1–100），其他/重复查询参数400。

添加只允许当前公开详情可见的 `AVAILABLE/RESERVED/EXCHANGED`，可收藏本人公开物品；收藏不是可交换承诺，不检查/建立占用。不存在、`DRAFT/PENDING_REVIEW/HIDDEN` 等非公开目标统一404，即使是自己的非公开物品或此前已经收藏也一样。重复添加成功200且保留原关系、收藏时间和排序位置；并发添加只留下一个 `(user_id,item_id)` 关系。取消只删除当前会话的关系，不受物品可见性限制；不存在、未收藏、已取消均返回200及 `{itemId,favorited:false}`，不披露目标是否存在，不删除物品/历史/上传。再次添加会创建新的收藏时间与排序位置。

列表 data 沿用 `{records,total,page,size}`，record 为 `{itemId,favoritedAt,itemVisible,item}`：`favoritedAt` 为UTC ISO8601；`itemVisible=true` 时 item 为当前公开 `ItemView`，与 GET /items/{id} 一致；目标不可见时 `itemVisible=false,item=null`，不返回其标题、图片、状态、归属或其他非公开字段。占位仅保留本人此前收藏的 itemId 和收藏时间，D 显示“物品暂不可见”，允许取消且不打开详情。即使当前会话恰好拥有该物品，也不扩大收藏里的公开可见性。itemVisible 不能用于判断可交换性，RESERVED/EXCHANGED 仍是公开可见。

total 为当前用户全部收藏关系数，包含不可见占位；先按关系在数据库分页，再在同一只读快照读取物品可见内容，不因过滤造成短页/错误计数。按收藏 created_at/id 降序，时间相同用关系ID稳定排序；无收藏时 records=[]、total=0，超出末页 records=[]、total仍为全部关系数。物品下架不会自动删除关系或保留旧内容快照；将来目标再次公开可见，列表自然恢复当前内容（不代表本批提供重新上架功能）。

新增 Flyway V5 建立 cl_favorite，unique(user_id,item_id)、用户/物品非级联外键及分页索引，V1–V4不改。添加/取消都先锁目标物品再处理关系，与物品状态变更串行；列表不加物品写锁。接口响应是本次操作完成时的结果，随后另一个会话仍可改变该用户收藏，D 在失败/响应不确定时应回读，不用本地数组代替持久化。D-02 的接口、空值、幂等与错误说明见 [a02-favorites.md](a02-favorites.md)，已在 [Issue #17](https://github.com/429res/campus-loop/issues/17) 同步，页面接入/消费确认尚未完成。

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

Demand 字段：`id,ownerId,categoryId,categoryName,description,preferredTags,status,version,createdAt,updatedAt,offeredItems`。description 为纯文本可空串、最长2000；categoryId 为 ACTIVE 的正整数分类；preferredTags 必填数组，最多8项，每项非空白且最多20字符，服务端 trim、转小写、去重。`offeredItemIds` 必填数组，允许空，最多100个不同正整数。

`offeredItems` 及 offerable-items 的 records 字段：`itemId,title,categoryId,conditionLevel,status,offerable`，全部来自现有 cl_item 实时读取；offerable 同时校验仍属需求本人、AVAILABLE、无占用。该返回不构成未来可交换承诺。归属已转走的历史关联只返回 itemId、offerable=false，其他字段为null，避免继续披露他人物品的非公开状态。

编辑、状态切换、删除均要求当前非负整数 version；过期409，不覆盖任何字段或关联，成功 version+1，updatedAt 为UTC ISO8601。编辑仅更新提交字段，offeredItemIds 若提供则全量替换且和需求在同一事务内提交。切换 ACTIVE 时重新验证全部关联；INACTIVE 可以保留已失效的历史关联。字段编辑不重新保存未提交的关联，D 可用 offeredItemIds=[] 清空。

关联是多对多的候选集合：一条需求0–100件本人 AVAILABLE 且无占用的物品，同一物品允许出现在本人多条需求中；关联不复制物品、不更改 owner、不占用。重复ID或不存在物品400，外人物品403，不可提供状态/占用409。非法分类400；未登录401。被删除需求的读写404；其他越权403。并发更新按需求行锁与 version 条件串行，关联写入按物品ID升序锁定再校验。

停用保留详情与关联并可恢复 ACTIVE，`GET /demands?status=ACTIVE` 不包含 INACTIVE。删除置 DELETED 并保留原需求内容、关联和ID作为墓碑，普通读写不可再访问，不提供物理删除接口；关联外键禁止物理删除被引用的需求/物品，后续持久化消费者必须采用同样的非级联外键，历史引用不会被接口删除破坏。A-03已落实进行中交换所选需求的编辑/启停/删除冻结，返回409；具体边界见下文创建契约。

旧字段策略：cl_item.wantedCategoryId/wantedTags 仍是旧发布与 GET /matches 的唯一来源；独立需求是上述 CRUD 的唯一来源。不回填、不双写、不自动同步或关闭旧字段，B-01 的停用/删除只影响独立需求，不改变旧推荐结果。B-02的显式独立入口、需求选择与规则版本见下节；本轮不退出旧链路，消费确认仍单独跟踪。requiredTags/最低成色既不接收也不隐式启用。

需求迁移使用 V4，保留 main 已合入的 V3 用户状态管理迁移及 V1/V2 原文。A/D 的候选基数复核仍待团队回复；不把未收到的确认写成已完成。接入样例和状态见 [b01-independent-demands.md](b01-independent-demands.md)。

## B-02 独立需求推荐

B-01 与 B-02 已合入 main；本节是 D-02 可调用的真实推荐契约，但不代表正式交换已实现。收藏接口另见上文 A-02 第二批。兼容和多需求策略见 [B-02 说明](b02-independent-matching.md)。

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

## A-02 第四批：物品审核（V7）

规则已由用户确认并同步 [Issue #23](https://github.com/429res/campus-loop/issues/23)。以下为本 PR 实现的接口，不沿用旧 `/admin/reviews/{id}/decision` 草案。

| 接口 | 权限与契约 |
| --- | --- |
| GET /admin/items | ADMIN；原分页增加可选 status，支持全部七种物品状态；keyword 搜标题≤100，categoryId>0，page≥1，size1–100；total 为全部命中过滤条件的物品，createdAt/id 降序 |
| GET /admin/items/{id} | ADMIN；包含非公开物品，正整数 id；不存在404 |
| POST /admin/items/{id}/review | ADMIN；严格 `{version,decision,reason}`，decision=APPROVE/REJECT，reason trim 后1–1000字，两种决定均必填；version 为非负 int32 JSON 整数。仅待审且无占用、无进行中交换引用；成功200返回数据库回读 ItemView，版本+1 |
| GET /admin/items/{id}/review-audits | ADMIN；page=1,size=12（1–100），newVersion/id 降序；PageResult，total 为该物品全部审计事件数；越界空 records 保留 total |

新发布 `PENDING_REVIEW/UNREVIEWED/version=0`；本人完整编辑未占用 `AVAILABLE/PENDING_REVIEW/REJECTED` 后进入待审、version+1，内容相同也复审。待审批准→AVAILABLE/ADMIN_REVIEW，驳回→REJECTED/UNREVIEWED。只有待审可决定；DRAFT/RESERVED/EXCHANGED/HIDDEN 不可编辑或审批；下架仍仅未占用 AVAILABLE→HIDDEN，本批不恢复上架。所有决定、编辑、下架共享物品锁及版本检查，任何过期占用行或 AWAITING_CONFIRMATION/READY/DISPUTED 参与者引用都409，不释放或越过交换状态。

公开读取、收藏可见性只允许 AVAILABLE/RESERVED/EXCHANGED；待审/驳回仅当前本人及 ADMIN 可读。本人列表含 REJECTED；收藏保留不可见占位，total口径不变。旧/独立推荐、需求可提供物品均只消费 AVAILABLE，无算法扩展；已关联物品编辑后 offerable=false，保留关系。

ItemView 增量字段：`reviewBasis`=LEGACY_DIRECT/UNREVIEWED/ADMIN_REVIEW；`reviewDecision,reviewReason,reviewedByName,reviewedAt,reviewedVersion` 仅本人/ADMIN读取返回最近一次决定，其余入口均为null。reviewedVersion 是被决定的旧物品版本，可能早于当前复审版本；无决定时五项均null，不能据此把待审表示为已批准。UTC时间为 ISO8601 Z。公开字段 reviewBasis 只表示发布依据；不表明履历核验或交换完成。

审计记录字段：`id,itemId,operatorUserId,operatorDisplayName,action,reason,previousStatus,newStatus,previousVersion,newVersion,previousSnapshot,newSnapshot,createdAt`。action=SUBMIT/APPROVE/REJECT/WITHDRAW；发布 previousSnapshot/status/version=null、newVersion=0；提交/下架 reason=null，审核理由必填。快照保留当时内容、owner、状态与版本，处理人显示名也存当时值；审计无修改/删除接口，不向公开或其他用户暴露。审计与物品变更同事务；unique(item,newVersion) 和 RESTRICT 外键保护。

错误：未登录/会话失效401，非ADMIN403，id不存在404，未知字段/错误类型/无效枚举/空理由400；旧版本、版本上限、非待审、占用/交接冲突409。成功重新回读；409保留理由及原version，回读内容后显式核对，不自动套用新版本重试。

V7 不重解释旧数据：现有 AVAILABLE 保持公开及推荐，现有 RESERVED/EXCHANGED 保持交换状态；三者标记 LEGACY_DIRECT，不伪造管理员或批准事件。其余旧状态保留并标记 UNREVIEWED，全部旧 version不变；首次合法编辑保存旧内容快照后进入待审。新建演示夹具明确写 LEGACY_DIRECT，重复启动不覆盖数据。需同批部署后端、C审批及D提交/本人状态读取；接入、示例和验证见 [a02-item-review.md](a02-item-review.md)。

## B-03 第一切片与 A-03：正式创建与本人读取

B-03 [PR #28](https://github.com/429res/campus-loop/pull/28) 的领域入口在本分支接入 A-03 唯一事务实现，正式创建返回200并回读持久详情。创建政策沿用 [Issue #25](https://github.com/429res/campus-loop/issues/25)；事务、锁序、失败样例和验证见 [A-03接入说明](a03-exchange-transaction.md)。

| 方法/路径 | 当前能力 |
| --- | --- |
| GET /exchanges/mine | 登录；服务端按参与者关系限定本人，page=1、size=12（1–100）、可选status；按createdAt/id降序，返回PageResult<ExchangeView> |
| GET /exchanges/{id} | 登录且参与者，ADMIN也不例外；非参与者与不存在均404，非法ID400 |
| POST /exchanges | 登录；仅接受下述严格命令；成功/精确重放200返回ExchangeView；参数/类型错误400，旧规则/过期推荐/占用/幂等内容冲突409，非参与发起人403 |

列表status为AWAITING_CONFIRMATION/READY/COMPLETED/CANCELLED/EXPIRED/DISPUTED，空串或未知值400；超页返回空records及真实total。只允许所列参数，ownerId/userId、重复参数400；详情和创建不接受查询参数。所有读取无FOR UPDATE和业务写入，不因截止已过自动改状态或释放占用。异常、不完整的已存环返回409，不拼出虚假流向。

ExchangeView字段：`id,initiatorId,status,version,createdAt,expiresAt,participants,flows,allowedActions,cancelledBy,cancellationReason,cancelledAt`。时间UTC ISO8601；participants按有向环从最小物品ID起点返回，含`userId,displayName,offeredItemId,receivedItemId,confirmationStatus,confirmedAt,handedOffAt,receivedAt`；confirmationStatus仅由confirmedAt是否存在映射PENDING/CONFIRMED。flows为`itemId,fromUserId,toUserId`，来源是持久参与者记录，不能用物品当前owner重建历史流向。displayName是当前公开显示名；V8在内部保存创建与所选需求快照；读取不暴露摘要、幂等键、私人需求说明或内部快照，不拼接后来私人物品字段。`allowedActions`按B-03.2/B-04实际动作资格返回CONFIRM/CANCEL/HANDED_OFF/RECEIVED/DISPUTE或空；取消审计三字段未取消为null，时间UTC ISO8601。查看记录不等于获得确认、取消或交接权限。

创建请求固定为`{ruleVersion,idempotencyKey,flows:[{itemId,itemVersion,demandId,demandVersion},...]}`，flows长度2/3，表示本项物品提供给下一项物品的当前所有者，最后一项流向第一项；需求属于该接收者且关联其环内提供物品。ID为不同正整数，版本为非负int32 JSON整数，不接受字符串/小数/null；所有字段必填，不接受owner/参与者/理由/分数/状态/时间。幂等键为8–64位`[a-z0-9_-]`，不trim或大小写折叠。请求体与每条流向均拒绝未知字段。

仅支持independent-v2。B-02响应增量提供`participants[].itemVersion`和`flows[].demandVersion`，从同一次数据库快照取得；结合flow.itemId/demandId组装命令，不能向他人的私有需求详情索取版本。增量元数据不修改硬条件、分数、排序或规则版本。缺少版本时刷新独立推荐，不填0、不复用旧wanted结果。推荐仍不预约物品。

用户已明确确认：发起人也不自动确认，创建后全员PENDING、AWAITING_CONFIRMATION/version=0；expiresAt为DB UTC创建时间+24h；进行中（AWAITING_CONFIRMATION/READY/DISPUTED）所引用需求禁止编辑/启停/删除。V8 cl_exchange_demand保存精确需求引用与历史快照；需求创建/修改先锁所属用户，编辑/启停/删除在需求锁内检查进行中引用，存在引用则409且版本不变。未选中需求不冻结；终态后允许按原需求契约修改，快照保留不变。

阶段二审查补齐创建边界：每条所选需求最多参与一个进行中交换，即使另一方案使用不同物品，也在需求锁内返回409；同键同摘要的原交换重放仍先成功返回。取消/超时后可重新选用该需求，完成后需求已INACTIVE，遵循原启用规则。创建时物品version最多为2147483645（预留占用、释放或流转两次递增），所选需求version最多为2147483646（预留完成关闭一次递增）；超过业务余量返回409且无写入，请求类型范围仍为非负int32。

独立推荐与创建共用的需求输入排除进行中已选需求；同一物品关联的其他可用需求仍参与原分类匹配及标签排序。取消/超时提交后，原需求恢复候选资格。推荐读取保持只读，不建立需求或物品占用；并发变化仍由创建锁内复核拒绝，不能自动替换提交的需求。

唯一调用链为B的ExchangeApplicationService → A主责ExchangeCreationTransaction → B纯ExchangeCycleValidator。端口唯一实现 DefaultExchangeCreationTransaction 在同一个READ_COMMITTED事务内检查幂等、锁定及重读数据库、调用验证器、持久化交换/参与者/占用/需求快照、将物品置RESERVED且version+1，提交后返回详情。ExchangeCandidateReader是无锁领域核查适配器，无公开预检端点，其结果不能授权写入。

幂等作用域`(服务端initiatorId,idempotencyKey)`；摘要含ruleVersion和整条绑定流向的itemId/itemVersion/demandId/demandVersion。按最小物品ID旋转、保留方向，旋转起点不同视为同请求，反向三环不同。同键同摘要应先返回原交换，不以首次创建造成的RESERVED/版本递增拒绝重放，不刷新截止时间；同键不同摘要409。状态/物品版本/需求版本或B-02所选需求变化409，不能悄悄替换新需求；越权发起403、非法重复用户/物品或环形状400、有效需求规模超限422。任何校验/唯一约束失败整体回滚，无部分占用/参与者。死锁等数据库瞬态冲突最多重试三次，每次新事务；耗尽返回409，客户端保持同一逻辑提交的键。V2历史行缺摘要时同键拒绝409，不推断成可重放请求。

C-03管理读取由本分支新增下述独立ADMIN接口；管理读权限不映射为代确认/代交接权限。D-03按已有参与者接口接入创建及后续操作，保持同一逻辑提交的幂等键，409保留选择/理由与原version，回读后由本人重新决定。确认/取消按B-03.2契约；交接与争议登记按B-04契约。

### C-03 管理交换读取契约

| 方法/路径 | 权限、参数与返回 |
| --- | --- |
| GET /admin/exchanges | 仅ADMIN；page默认1、size默认12且1–100，可选status为六种交换状态，省略表示全部，空串/未知/重复或额外查询参数400；返回PageResult<ExchangeView>，createdAt/id降序、超页空records且total保留 |
| GET /admin/exchanges/{id} | 仅ADMIN；正整数id、无查询参数；返回AdminExchangeDetail，不存在404、非法参数400 |

AdminExchangeDetail为`{exchange,events,creation}`。exchange沿用ExchangeView，但allowedActions固定为空；参与关系和流向来自持久记录，不用当前owner重建。events按newVersion/id升序，字段`id,eventType,actorId,actorDisplayName,previousStatus,newStatus,previousVersion,newVersion,reason,occurredAt`，actorDisplayName为当前公开名称，系统到期actorId/name为null；无历史事件返回空数组，不补造确认。creation为创建时安全快照或null，字段`ruleVersion,flows:[{itemId,itemTitle,fromUserId,toUserId,demandId,matchedCategoryName,reason}]`；来源仅原creation_snapshot，不用当前需求或物品私有数据补齐。旧记录无快照时返回null，由页面说明历史数据不完整。不可返回原始creation_snapshot、requestDigest、idempotencyKey、需求私人description、账户凭据或技术退避字段。

未登录/失效会话401，非ADMIN403，数据完整性异常409。两个接口只读且使用同一数据库快照，不锁业务行、不触发到期、不写状态或审计；原参与者GET/写入口继续维持非参与者404，不因管理员有读取权限放开写动作。无新迁移。

## B-03.2：参与者确认与取消

A-03 PR #32创建与本片生命周期共用事务执行器及数据库UTC；本片POST `/exchanges/{id}/confirm`严格接收`{version}`，POST `/exchanges/{id}/cancel`严格接收`{version,reason}`。version必填非负int32 JSON整数；reason trim后1–1000字，禁止未知字段、客户端身份及查询参数。成功和精确重放200返回当前ExchangeView。

用户已确认并在[Issue #30](https://github.com/429res/campus-loop/issues/30)同步：全员独立确认后READY，任一参与者在原24h截止前且无交接时可取消等待或READY；截止等号及以后拒绝新确认/取消。确认重试限未截止/未交接的等待或READY；已取消只允许原取消者相同原因精确重放，旧version可用、未来version409，不再次写事件或释放。新变化要求当前version。

非参与者含ADMIN均404；参数400、未认证401、锁内操作人停用403、状态/截止/版本/占用或数据完整性冲突409。缺少V8创建依据的旧记录只读，动作空、写409。409保留原因/原version，回读后由本人决定，不自动覆盖版本。详情取消字段为cancelledBy/cancellationReason/cancelledAt，仅读参与者可见；不公开内部快照、幂等键或私人需求。

`ExchangeLifecycleService`在exchange→用户→需求→物品/占用锁内调用`ExchangeLifecycleRules`，最终数据库UTC决定权限；状态、参与者时间、取消审计、事件、按item_id+exchange_id条件释放及物品version更新原子提交。allowedActions共用纯规则，等待邀请时未确认为CONFIRM/CANCEL、已确认为CANCEL；READY交接动作见B-04，未开始且已截止或终态为空；按钮不替代鉴权和锁内重校验。V9追加取消字段和按exchange/new_version唯一的审计事件，旧行不伪造审计。

共同内部expire入口由A-04定时扫描接通，批次与持久退避在重启后继续处理。仅AWAITING_CONFIRMATION/READY、数据库UTC达到原expiresAt且无handedOffAt/receivedAt才EXPIRED；保留原24h截止，不延长、不释放其他交换占用。处理中或失败退避期间，GET可返回已到期活动状态且allowedActions为空，客户端回读结果，不能自行标记完成。无公共expire路径，技术重试字段不返回ExchangeView；运维和恢复证据见[A-04](a04-exchange-expiry.md)。交接及争议登记见B-04。状态矩阵、兼容与D/C样例见[B-03.2说明](b03-invitation-rules.md)。

## 后续接口与已接通切片

| 路径草案 | 语义/并发契约 |
| --- | --- |
| POST /exchanges/{id}/handoff | B-04已实现，见下述契约 |
| GET/POST /items/{id}/history | B-05.1已实现，参与者确认见B-05.2；管理员核验见B-05.3 |
| POST /reports | C-04 通用 ITEM 举报已实现；会话确定举报人，严格字段、私有证据和幂等规则见下节 |

### C-04 通用举报后端（本片实现）

V15 已落地通用举报、私有证据关联和追加审计；首批目标固定为 ITEM。C 的正式管理页面和 D 的用户表单仍需消费接线，不能将后端切片标记为整个 C-04/D-04 完成。

| 路径 | 权限与语义 |
| --- | --- |
| POST /api/reports | 登录用户严格提交 `{targetType,targetId,reason,evidenceUploadIds,idempotencyKey}`；服务端确定 reporter |
| GET /api/reports/mine、GET /api/reports/mine/{id} | 仅举报人读取本人分页/结果；目标失效保留安全摘要并返回 `targetAvailable=false` |
| GET /api/reports/mine/{reportId}/evidence/{evidenceId}/content | 仅举报人读取本人已关联证据 |
| GET /api/admin/reports、GET /api/admin/reports/{id} | ADMIN 队列筛选/分页和授权详情；全文原因与证据只进入详情 |
| POST /api/admin/reports/{id}/accept | ADMIN 严格提交 `{version,reason}` 受理，成功数据库回读 |
| POST /api/admin/reports/{id}/decision | ADMIN 严格提交 `{version,decision,reason}` 处理，成功数据库回读 |
| GET /api/admin/reports/{id}/audits | ADMIN 追加式审计分页；不提供修改或删除 |
| GET /api/admin/reports/{reportId}/evidence/{evidenceId}/content | ADMIN 授权内容读取；不返回服务端文件路径或接受客户端任意外链 |

`targetType` 只接受 ITEM；目标提交时须处于 AVAILABLE/RESERVED/EXCHANGED。`reason` trim 后 1–1000 字，证据为 0–5 个不重复的本人 PRIVATE_EVIDENCE 上传 UUID，幂等键为 1–64 位小写字母、数字或 `._:-`。五个字段必填；reporter、status、decision、处理身份或其他未知字段一律 400。同键同规范化内容返回同一举报的最新回读，同键不同内容 409；同一举报人/目标已有 SUBMITTED 或 IN_REVIEW 时换键仍 409，终局后才可基于新事实再次提交。

状态固定为 `SUBMITTED(version=0) → IN_REVIEW(version=1) → RESOLVED(version=2)`，决定为 UPHELD/DISMISSED。任一有效 ADMIN 可决定已受理记录，受理人不独占；acceptedBy/decidedBy 分开留痕。相同管理员完全相同的 actor/action/version/reason/decision 重试返回当前回读且不重复审计；其他旧版本、不同管理员或不同内容 409 且不覆盖。C 收到 409 后重新读取详情和列表，保留未提交理由，不自动换用新版本重放。

列表字段为 `id,targetType,targetId,targetAvailable,targetSummary,reporterDisplayName,status,version,createdAt,acceptedBy,acceptedAt,decision,decisionReason,decidedBy,decidedAt`，不含全文举报理由或 evidence；详情追加 `reason,evidence`。证据只暴露不透明 `id,displayName,contentType,size,accessStatus`，不返回 uploadId、哈希、路径或公开 URL。本人和 ADMIN 分别通过受控内容端点读取；内容为 PNG、private/no-store/nosniff，文件缺失/变化后拒绝。处理人与审计 actor 只返回 `{displayName}`，不返回用户 ID、请求摘要、幂等键、口令或 token。

分页默认 1/12，`page≥1`、`1≤size≤100`；本人支持 status/targetType，ADMIN 另支持 keyword（举报编号或目标摘要）。未知/重复参数 400。401/403/404/409 保持真实 HTTP 语义。完整字段、失败样例、锁序、C/D 接线与验证见 [通用举报说明](c04-generic-reports.md)。普通举报决定不会改变物品、交换、需求、占用或履历；EXCHANGE 目标 400，B 尚未提供管理员争议后果服务，不能借此接口绕过 B-04 状态机。



精确DTO限制、已预留路径和后端实现入口见 [backend-contract.md](backend-contract.md)。

### B-04 交接和争议登记（已实现）

`POST /exchanges/{id}/handoff`：严格请求 `{version,kind,acknowledged:true,note?}`，kind仅HANDED_OFF/RECEIVED，note可省略、trim后0–1000字。身份、物品和时间来自服务端；每人分别确认 offeredItemId 已交出、receivedItemId 已收到，不允许指定owner/物品/对方/时间。两人4次、三人6次声明才完成；新动作当前version，已提交同类同说明可用旧但非未来version重放（含COMPLETED/DISPUTED），不覆盖声明或追加事件。不同说明409。

READY首次交接必须原截止前；任一handedOffAt/receivedAt非空后允许原截止后继续，不能取消或自动到期。最后声明同事务迁移所有owner、置EXCHANGED/version+1、仅精确满足且ACTIVE/版本/引用一致的需求置INACTIVE/version+1、追加BOTH_CONFIRMED履历并释放本交换占用、COMPLETED。EXCHANGED仍遵循既有公开读取，但不参与推荐或再次提供。

`POST /exchanges/{id}/dispute`：严格请求 `{version,reason}`，原因trim后1–1000字，仅READY已开始交接的本人参与者可登记，原截止不阻止登记；转DISPUTED保留owner、需求和占用。原登记者相同原因可用旧但非未来版本重放；裁决与恢复推进未实现。上述成功返回最新ExchangeView；未知字段400，失效身份401、不可用参与者403、非参与者404、状态/版本/引用冲突409。GET仍只读，管理员没有代办权。

ExchangeView追加disputedBy/disputeReason/disputedAt（无争议null），participants追加handedOffNote/receivedNote（未声明null，可选说明省略后为空串）。READY未开始且未截止allowedActions=[HANDED_OFF,RECEIVED,CANCEL]；开始后按本人未提交类别返回HANDED_OFF/RECEIVED及DISPUTE；终态空。等待期仍遵循B-03，动作集合与锁内规则共用。两方/三方、错误、精确重放与C/D恢复样例见[B-04](b04-exchange-handoff.md)。

#### C-04 管理员争议读取（本片实现）

仅ADMIN的 `GET /admin/exchange-disputes`、`GET /admin/exchange-disputes/{id}`、`GET /admin/exchange-disputes/{id}/events` 已接通，只读DISPUTED。分页默认1/12、size≤100，详情沿用ExchangeView但allowedActions为空、私人交接说明为null；事件按版本升序，仅争议理由可见。字段、隐私、错误与C/D样例见[领域读取切片](c04-exchange-domain-read.md)。ITEM 通用举报已由上节独立实现；交换争议裁决后果仍未实现，二者不能混用。

#### C-04 管理员争议处理草案（未实现，不可调用）

C 的只读追溯夹具以现有 ExchangeView、持久 flows/participants 和 cl_exchange_event 事实展示 DISPUTED；不能调用参与者专属 `GET /exchanges/{id}` 冒充管理员读取。本片已提供独立 ADMIN 争议队列、详情与事件分页接口；交接私人说明和证据仍不披露。B-04 当前没有争议附件，B-05 履历私有证据也不能自动授权给争议模块。

管理员裁决的决定枚举、允许状态、利益冲突规则、`version/idempotencyKey` 请求、精确重放、裁决审计，以及每种决定对 exchange/item/demand/hold/history 的原子后果尚未确定。C 不提供裁决按钮或请求草案，也不从 UI 推导 owner 变更、释放占用、恢复交接或实物回滚。未来接口必须调用 B 的同一交换事务入口；409 后返回/读取最新交换与事件事实，保留理由但不自动换版本重试。D 的本人结果投影应与该事务同源，且不暴露管理员 ID、内部快照、他人私密证据或存储路径。

### B-05.1 自述履历、私有证据与查询（已实现）

GET `/items/{id}/history?page=1&size=12`及GET `/items/{id}/history/{eventId}`支持匿名公开基础字段和可选有效认证，page≥1/size1–100、recordedAt/id降序；错误/重复/未知参数400，有无效Authorization则401。公开物品沿用AVAILABLE/RESERVED/EXCHANGED；非公开物品仅当前owner/ADMIN读取全部基础履历，其他作者或该件关联交换交出/接收双方按事件过滤（含total），无权限404。当前owner不自动获得旧私有证据。

POST `/items/{id}/history`严格接收`{eventType,statement,occurredAt,timeUnknown,relatedExchangeId,correctsEventId,evidenceUploadIds}`，七字段必填。eventType仅REPAIR/TRANSFER，声明trim后1–2000字；未知时间须null/true，已知须UTC ISO8601秒精度/false且在可声明持有时间窗口、不晚于数据库UTC（最早1970-01-01T00:00:01Z）。两种关联ID可为null或正整数；证据为0–5个不同私有上传UUID。作者/来源/记录时间/owner等越权字段一律400；只写SELF_REPORTED，不转移物品或修改B-04交换事实。

当前owner自述本人持有期间；曾经owner须relatedExchangeId对应B-04已完成的该物品本人交出记录；未完成交换409、其他人403。修正仅本人SELF_REPORTED追加correctsEventId，继承原关联/时间窗口，禁止覆盖原文；同一节点最多一个直接修正，旧链尾409。旧缺乏授权窗口自述只读。无PUT/PATCH/DELETE覆盖路径，方法不支持返回405；参与者追加确认和管理员核验不在本片。

POST `/uploads/evidence`复用现有图片校验/重编码与本地存储，返回`{uploadId}`；证据存公开目录之外。只允许本人PRIVATE_EVIDENCE引用，旧PUBLIC上传不能转成私有证据，也不能把私有证据作为公开物品图片。GET `/history-evidence/{uploadId}`须登录，仅上传者预览或其已关联事件授权者（作者/该件交换的交出与接收双方/ADMIN）可读，其余404。三方环的另一人不自动授权；ADMIN不预览他人未引用上传。返回image/png、private/no-store、nosniff，无静态私有路径。公开POST /uploads仍返回原{url}且现有图片语义不变。

HistoryView为`id,itemId,eventType,statement,evidenceLevel,authorDisplayName,occurredAt,timeUnknown,recordedAt,correctsEventId,correctedByEventId,confirmedAt,verifiedAt,authorId,relatedExchangeId,evidence,canCorrect`。无私有授权时authorId/relatedExchangeId/evidence均null，不泄露证据数量；有权限时evidence为`[{uploadId,url,mediaType}]`。来源保留SELF_REPORTED/BOTH_CONFIRMED/ADMIN_VERIFIED；本片仅提交前者，B-04仍由原始事务生成中者。JSON读取不缓存，响应按Authorization区分。不同来源、权限矩阵和C/D恢复样例见[B-05.1](b05-self-reported-history.md)。

### B-05.2 参与者确认（已实现）

范围、严格请求字段、隐私扩展、来源转换与冲突恢复见[B-05.2](b05-participant-confirmation.md)。本片自动化及隔离MySQL证据见该说明。

HistoryView新增`recordedEvidenceLevel`与`confirmation`：原来源始终保留；仅实际N/N确认时evidenceLevel展示BOTH_CONFIRMED，confirmedAt取全部完成时间。confirmation包含mode/status/confirmedCount/requiredCount/currentContent/requestedAt/completedAt/withdrawnAt；仅作者、明确授权成员及ADMIN返回snapshotHash/snapshot/participants/withdrawalReason。公开不返回参与者或证据标识。修正依据recordedEvidenceLevel=SELF_REPORTED；显示来源已获参与者确认的自述仍可由原作者追加新修正，旧确认不继承。

### B-05.3 管理员单事件核验（已实现）

管理队列、详情、决定、版本与幂等字段、证据门槛及利益冲突规则见[B-05.3](b05-admin-verification.md)。HistoryView增加verification；通过时仅本事件evidenceLevel显示ADMIN_VERIFIED，原recordedEvidenceLevel/confirmation保留。私有理由/快照仅ADMIN；普通时间线不返回核验人ID或证据摘要。

### B-06 性能切片兼容

分类建边索引仅改变independent-v2的内部边构建；字段、结果顺序、理由与全部422上限不变，没有候选分页或部分推荐成功。正式创建仍实时事务重校验。见[B-06基准与边界](b06-matching-benchmark.md)。
