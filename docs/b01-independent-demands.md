# B-01：独立需求与本人可提供物品关联

状态：B-01 已通过 [PR #4](https://github.com/429res/campus-loop/pull/4) 合入 main，模型和迁移为后续切片的实际基础。历史协作 [Issue #3](https://github.com/429res/campus-loop/issues/3)。本切片仅后端；B-02功能分支的显式独立匹配入口见 [B-02说明](b02-independent-matching.md)，D-02页面和正式交换仍未接入。

## 兼容与迁移

- 独立需求以 `cl_demand` 为唯一来源，候选集合用 `cl_demand_item` 关联已有 `cl_item`。物品所有者仍只由 cl_item 管理，不创建第二套物品。
- 一条需求可有0–100件候选，同一物品可出现在本人多条需求中；这是保存选择的基数，不是交换规则或物品占用。写入要求本人、AVAILABLE、无占用；读取展示实时 offerable。
- 旧 `wantedCategoryId/wantedTags` 继续供旧发布和 `/api/matches` 使用，无回填、双写、自动同步或清空。B-01 增改停用删除不改变旧推荐结果。
- requiredTags、最低成色、规则版本和新旧匹配切换留给 B-02 与 A/D 确认。本轮请求这些未开放字段会400，不能提示用户它们已成为筛选条件。
- 需求与关联表使用 V4，保留 main 已合入的 V3 用户状态管理迁移以及 V1/V2、既有物品数据。B-01 原暂定 V3 仅改变文件序号，SQL 原文不变；本次修复已确认旧 B-01 V3 未用于需要保留数据的开发库。A/D 的候选基数复核仍待回复，未冒称已得到确认。
- 从 main 升级时正常运行 V4。旧 B-01 分支的临时测试库若已登记需求 V3，不能将其迁移历史当作 main 的 V3；使用新的隔离测试库验证，不对既有历史自动 repair。迁移文件更名后先在 `sys-project` 执行 `./mvnw clean`（Windows 为 `mvnw.cmd clean`），清除旧构建产物中的 V3 需求 SQL，再按根 README 构建/测试。

## 给 D-02 的接口样例

以下请求路径均带 `/api`。认证使用已有请求封装添加本机登录会话；样例不包含令牌。所有ID均为示意，物品ID必须先从本人候选接口读取。

无物品也可创建：

```http
POST /api/demands
Content-Type: application/json
```

```json
{"categoryId":2,"description":"想找便携电子用品","preferredTags":["便携"],"offeredItemIds":[]}
```

统一成功响应（ID和时间仅示意）：

```json
{
  "code":200,"msg":"成功",
  "data":{
    "id":42,"ownerId":7,"categoryId":2,"categoryName":"数码电子",
    "description":"想找便携电子用品","preferredTags":["便携"],
    "status":"ACTIVE","version":0,
    "createdAt":"2026-09-08T01:00:00Z","updatedAt":"2026-09-08T01:00:00Z",
    "offeredItems":[]
  }
}
```

刷新读取 `GET /api/demands/42`；本人列表 `GET /api/demands?page=1&size=12`，有效需求 `GET /api/demands?status=ACTIVE`，分页结构是 `{records,total,page,size}`。状态筛选不接受 DELETED。

选择物品先调用 `GET /api/demands/offerable-items?page=1&size=12`，只返回本人 AVAILABLE 且无占用的已有物品。不要用公开 `/items` 列表当作本人选择器。保存候选用：

```http
PATCH /api/demands/42
Content-Type: application/json
```

```json
{"version":0,"offeredItemIds":[81,82]}
```

`offeredItemIds` 是全量替换，可传 `[]` 清空；未提交的字段保持原值，显式null报400。可编辑字段只有 categoryId、description、preferredTags、offeredItemIds；每次PATCH至少提交其中一项。不能把整个响应对象原样回传，ownerId/status/时间等字段不允许出现在编辑请求中。

响应 offeredItems 的每项为 `{itemId,title,categoryId,conditionLevel,status,offerable}`，查询当前物品状态，不存第二份物品快照。历史关联可能因下架、占用或所有者变化而 offerable=false；若所有权已转走，除 itemId/offerable 外其他字段为null。允许用户移除失效项；切回 ACTIVE 前必须消除失效关联。

停用与恢复通过 `PATCH /api/demands/42/status` 提交 `{"version":1,"status":"INACTIVE"}` 或 ACTIVE。停用后本人仍能查看和编辑，ACTIVE列表立即排除。停用不会改变旧演示推荐；B-02新独立入口会排除该需求。D-02接入时须明确区分两个来源，不能通过回退旧入口补出已停用的独立需求推荐。

删除通过 `DELETE /api/demands/42?version=2`，成功 data 为 `{"id":42,"status":"DELETED","version":3}`。删除不可从API恢复，详情和后续写入404；数据库保留需求原内容、关联和ID作为历史墓碑。前端移除该项，不把停用当成删除。

## 错误恢复

- 400：非法分类、空白/超长标签、重复或不存在的候选ID、分页错误、缺少version、额外/空值字段；保持表单，让用户修正。
- 401：按现有登录过期流程重新认证。
- 403：需求或物品不属于当前用户；管理员也不能代替本人写入。
- 404：需求不存在或已删除；刷新列表，停止重试原写入。
- 409：过期version、不可提供的物品或物品占用；保留本地编辑内容，重新GET详情/候选，让用户比较后再提交最新version。不得自动提高version覆盖其他设备的更新。

所有写操作成功后用服务器返回值替换本地版本；不要依靠时间戳或本地递增猜测version。成功与错误沿用 ResultVo，HTTP错误状态与code一致。

## 删除保护和未来消费者

DELETE 仅将需求置为DELETED，保留内容与关联，保证历史ID不悬空；没有物理删除入口。关联表对需求和物品用非级联外键，数据库也拒绝直接删除被引用对象。未来交换/履历持久引用需求时同样必须采用非级联外键，并保存业务快照；正式引用下的额外编辑/停用限制由 B-03 与 A 共同确定。本切片不创建交换、不写占用、不转移所有权，也不提供通用“创建引用”接口。

## 验证记录

提交 `a68e365` 于 2026-09-08 在 macOS arm64、JDK 17、Maven Wrapper 下实际运行（以下 V3 指当时尚未合入 main 的需求迁移，当前已顺延为 V4）：

| 层次 | 本次证据 |
| --- | --- |
| H2 自动化 | `node scripts/run.mjs test`：8项匹配 + 18项 Spring MVC/Flyway/Mapper 集成测试，共26项通过；新增9项 B-01 用例 |
| 全新 MySQL | `node scripts/mysql-test.mjs`：一次性 MySQL 8.4.11，从空库应用 V1–V3，同样26项通过 |
| MySQL 升级 | 第二个一次性容器先运行 `08f5440` 的 V1/V2及17项原测试，保留其虚构数据；随后当前版本升级到V3并通过26项测试。逐行比较原有 cl_item 全部字段一致，包含旧 wanted 字段、owner、状态和版本 |
| 具体边界 | 无物品用户创建后新登录会话回读；401/403、未知/空值字段、非法分类、重复/外人/失效/占用关联；并发编辑200+409；停用/删除、墓碑内容和FK保护；旧发布/2–3环推荐回归 |
| 代码检查 | `git diff --check`、`node scripts/repository-check.mjs` 通过；本地编译通过；独立静态评审发现的读取快照及标签存储容量问题已修正 |
| 构建与 CI | 本地未重跑前端构建；推送后的后端 verify、三种前端构建及 Windows/MySQL CI 以 PR 检查为准 |
| 浏览器/微信 | 未执行；本轮无页面修改，D-02 页面及微信联调属于后续 |

MySQL 请求通过真实 Spring MVC/认证/事务/MyBatis 对真实数据库执行（MockMvc 入口），没有用内存数据库替代 MySQL，也未声称经过浏览器或外部HTTP网络链路。两个临时测试容器均已自动停止并删除；未连接成员日常库，随机口令和会话仅驻内存、不输出。升级验证复用一次性测试脚本的容器生命周期，临时工具未纳入产品脚本。Flyway 对 MySQL 8.4 的既有支持版本提示仍出现，本次迁移和测试实际通过，未夹带依赖升级。

### 同步 main 后的验证

2026-09-08 同步 main `1bf1b78`，解决文档冲突并将 B-01 需求迁移顺延为 V4。Maven clean 后管理端、H5、小程序构建及后端 verify 全部通过；H2 与全新一次性 MySQL 各31项（8项匹配、23项集成）通过，覆盖合并后的 A-01 与 B-01。

额外执行一次性 MySQL 的主线 V3→V4 升级：旧用户/version、启停审计和物品逐字段保留，V1–V3历史与校验值不变，需求/关联可写且约束有效，再次迁移0项；专项1项通过。临时容器自动清理，无日常数据库访问。此轮未执行浏览器或微信交互，最新云端 CI、其他成员审核及 A/D候选基数复核以 PR #4 为准。完整分层记录见 [verification.md](verification.md)。
