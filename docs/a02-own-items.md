# A-02 第一批：本人物品列表、编辑与下架

本批后端和 D 接入契约随功能分支交付，协作见 [Issue #15](https://github.com/429res/campus-loop/issues/15)。从 main 的 `c6f4649` 开始实现；该基线已合入 B-01 独立需求，B-02 推荐 PR #11 仍独立评审，C 审核 PR #14 只提供前端只读页面和开发夹具。未将其他分支实现混入本 PR。

## 状态与范围

| cl_item 状态 | 本人列表/详情 | 公开列表/详情 | 本批编辑/下架 |
| --- | --- | --- | --- |
| AVAILABLE | 可读 | 可读 | 无占用、无进行中交换引用时可操作 |
| HIDDEN | 可读 | 不可读 | 均409；重新上架另批确认 |
| DRAFT / PENDING_REVIEW | 可读 | 不可读 | 均409；尚未实现草稿/审核写流程 |
| RESERVED / EXCHANGED | 可读 | 可读，保持既有契约 | 均409；不能绕过交换状态机 |

进行中的交换引用指 `cl_exchange_participant.offered_item_id` 对应 `AWAITING_CONFIRMATION/READY/DISPUTED` 交换；即使物品误为 AVAILABLE 且缺少占用行，也拒绝编辑/下架。任何占用行都阻止操作，包括已经过期的行；本接口不承担释放职责。终态交换引用保留历史，不阻止一件当前 AVAILABLE 且无占用物品下架；当前仍为 EXCHANGED 时则不能操作。

发布仍为 AVAILABLE，不引入审核准入或迁移。下架只改 status 为 HIDDEN、version 加1，不删除正文、图片引用、上传文件、需求关联、交换参与者或履历。HIDDEN 退出现有推荐候选和需求 offerable-items；独立需求记录不被自动停用或改写。B-02 后续合入时继续沿用 AVAILABLE 过滤。

## D 的表单接入

- 从 `GET /api/items/mine` 取得 `{records,total,page,size}`；身份和 total 均由服务端会话限定。支持 page、size、keyword、categoryId、status，默认全部六种状态；排序为 createdAt/id 降序。不要调用公开列表后在前端筛 owner。
- 打开编辑和冲突恢复使用 `GET /api/items/mine/{id}`。读取中没有客户端 owner 参数；管理员也不能替其他人访问本人详情或写入。
- `ItemView` 在原结构上新增整数 version，发布初值0。表单仅挑选下述可编辑字段，不能把整个 ItemView 原样展开提交，ownerName/categoryName/status/createdAt 等展示字段会被拒绝。
- `PUT /api/items/{id}` 是完整表单替换，复用发布字段校验。必须提交 version 与全部七个发布必填字段；tags/wantedTags 是数组，可为空。imageUrl 可省略/null/空白以移除引用；保留原图必须带回本人上传URL。服务端 trim 标题/说明，标签 trim、小写及去重。
- `POST /api/items/{id}/withdraw` 只提交 `{version}`。无硬删除、重新上架或客户端目标状态参数。
- 复用 `wx-project-self/src/common/http.js` 的请求、错误和会话处理；该封装当前没有导出 put，D 接入时需在同一 request 上补充 PUT 代理，不另建请求客户端。现有发布页的标签转换、图片上传和按账号隔离草稿逻辑可复用。编辑草稿应按当前 userId/itemId 保存，不得在切换账号后恢复他人物品内容。

虚构示例（物品ID用本人列表的实际结果替换；不含令牌或账号口令）：

```http
PUT /api/items/4101
Content-Type: application/json
```

```json
{
  "version": 0,
  "title": "校园阅读台灯",
  "description": "灯罩完好，已说明使用情况",
  "categoryId": 4,
  "conditionLevel": 4,
  "tags": ["便携"],
  "wantedCategoryId": 1,
  "wantedTags": ["教材"],
  "imageUrl": null
}
```

成功仍为 HTTP200 + `{code:200,msg,data:ItemView}`。该例成功后 version=1；下架提交 `POST /api/items/4101/withdraw`、`{"version":1}`，返回 status=HIDDEN、version=2，其他字段保留。列表和本人详情可回读该记录，公开详情404。

| 结果 | D 的处理 |
| --- | --- |
| 400 | 显示 msg、保留输入；检查必填、类型、未知字段、分类与图片归属；嵌套 Bean Validation 的字段路径可能是 fields.title 等 |
| 401 | 使用现有会话失效流程，草稿保持账号隔离；重新认证后再次回读本人详情 |
| 403 / 404 | 停止提交，提示无权访问或记录不存在，回到本人列表 |
| 409 | 保留输入并回读本人详情，展示新状态/版本供用户重新判断；不自动用新 version 覆盖服务端 |
| 网络失败/响应丢失 | 本人详情回读确认是否已提交，不假定失败或自动重复；相同旧 version 的重试不会再次覆盖 |
| 200 | 用服务器返回的 ItemView 更新视图，再按需要刷新本人列表 |

version 仅接受非负 JSON 整数；未知字段、数字字符串、小数版本、必填 null 均400。相同字段的有效编辑也推进版本；下架后重复下架409。返回 `AVAILABLE` 只是状态快照，客户端不能由此保证可编辑；最终以服务端占用和版本复核为准。

## 并发与存储

编辑和下架位于 READ_COMMITTED 事务：锁物品 → 验 owner/version/状态 → 检查占用/进行中参与者引用 → id/owner/status/version 条件更新 → 同事务数据库回读。等锁后读已提交状态，旧版本不会覆盖新数据。未来交换写操作须先锁相关物品（多个时按ID升序），所有修改物品状态/归属的流程同步推进物品 version。当前接口不锁 exchange 行，也不创建/释放交换或占用。

复用 V1/V2 已有的 version、HIDDEN 和交换表以及原上传归属查询；无需新增迁移，不修改 V1–V4。服务端更新只写白名单字段，owner、status（除专门下架）、createdAt 不能由编辑表单设置。

## 验证与剩余能力

2026-09-08 首轮运行 `node scripts/run.mjs test`：UniApp Node 测试26项、后端54项（16匹配 + 38 H2 API集成）全部通过。`node scripts/mysql-test.mjs` 在临时 MySQL 8.4.11 容器从空库运行 V1–V4，同一54项后端测试全部通过，0失败/错误/跳过；容器自动停止移除，未连接日常开发库。

新增8组物品集成测试在两种数据库均通过：数据库回读、六状态本人隔离、完整表单白名单/类型/边界、真实上传归属与移除引用、版本冲突、三种编辑/下架竞态、8个等待物品锁后的归属/占用/状态/交接引用复核场景、下架退出公开读取与推荐、履历和独立需求关联保留。交换写入仅为隔离测试夹具，没有正式交换API联调。

`node scripts/repository-check.mjs` 和 `git diff --check` 通过。Flyway 对当前H2/MySQL版本仍有既有兼容性提示，迁移与测试均实际成功。最终交付证据见 [verification.md](verification.md)。

本批没有 D 页面改动，不将单元/MockMvc + 数据库测试视作浏览器或微信联调。D 接入确认和 H5/微信验收仍待后续；正式交换、审核、重新上架、HIDDEN 编辑、收藏及分类写 API 未在本批实现。
