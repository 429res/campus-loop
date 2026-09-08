# A-02 第四批：物品审核后端与 C/D/B 接入

本批在 `feature/a02-item-moderation` 实现，基线 main `365430e`（分类 PR #21 已合入，独立需求 V4 已存在）。用户确认新发布/复审流程与旧 AVAILABLE 保留并标记历史直发；协作记录见 [Issue #23](https://github.com/429res/campus-loop/issues/23)。最终字段、分页与错误以 [API契约](api-contract.md#a-02-第四批物品审核v7) 为准。

## 状态与发布范围

| 当前状态 | 本人完整编辑 | 本人下架 | ADMIN决定 | 公开/候选 |
| --- | --- | --- | --- | --- |
| PENDING_REVIEW | 进入待审、版本+1 | 409 | 批准→AVAILABLE；驳回→REJECTED | 非公开，不作候选 |
| REJECTED | 修改后提交待审 | 409 | 409 | 非公开，不作候选 |
| AVAILABLE | 进入待审 | →HIDDEN | 409 | 公开；有效用户且无占用才作候选 |
| RESERVED / EXCHANGED | 409 | 409 | 409 | 公开历史，只读；不作候选 |
| DRAFT / HIDDEN | 409 | 409 | 409 | 本人/管理员可读；不作候选 |

所有写操作仍要求无任何占用行（包括过期行），没有 AWAITING_CONFIRMATION/READY/DISPUTED 交换参与者引用。先锁物品，再复核版本/状态/占用；条件更新与审计同事务。审核不会释放占用、修改交接、转移 owner、改写履历或修改需求关联。HIDDEN 恢复上架、DRAFT 提交和正式交换不在本批。本人编辑继续使用发布字段白名单与现有上传归属检查；客户端不能提交 reviewBasis/status/owner/审核结果。

V7 只新增审核依据列和审计表，扩展 REJECTED 与发布默认值，V1–V6 不改写。旧 AVAILABLE/RESERVED/EXCHANGED 保持状态、内容、版本与占用并标记 LEGACY_DIRECT；其他旧状态 UNREVIEWED。不给旧数据虚构管理员批准。新演示夹具显式历史直发，重复初始化不覆盖。首次合法编辑保存旧内容快照并转待审。

## C 接线

`GET /api/admin/items?status=PENDING_REVIEW&page=1&size=12` 可读待审总数；total按服务端筛选条件计算。详情使用 `/api/admin/items/{id}`，不依赖当前页。审核决定示例：

```json
{"version":0,"decision":"REJECT","reason":"请补充物品瑕疵说明。"}
```

发送至 `POST /api/admin/items/{id}/review`，200 的 data 为回读 ItemView，例如 status=REJECTED、version=1、reviewBasis=UNREVIEWED、reviewDecision=REJECT、reviewedVersion=0。理由 trim 后1–1000字，批准同样必填；旧 `/admin/reviews/{id}/decision` 只是已废弃草案，没有兼容别名。

管理页面真实接入状态筛选、详情、决定与审计分页；显示最近决定和被审核版本，历史直发单独标明。409保留理由和原version，禁用决定，只有点击“重新读取当前内容与版本”才加载当前内容供再次核对；不自动重试。非待审版本无法提交，其他错误保留输入。成功重新加载列表/详情。开发环境夹具仍明确与真实API分离。

`GET /api/admin/items/{id}/review-audits` 返回 SUBMIT/APPROVE/REJECT/WITHDRAW 事件、前后状态/版本/内容快照、操作者和UTC时间；unique(item,newVersion)，外键RESTRICT，无覆盖/删除接口。历史直发无伪造审计，首次编辑记录其原内容。

## D 接线

发布成功不再意味着公开：本 PR 将用户端发布成功导航改为 `detail?id=...&mine=1`，通过 `GET /api/items/mine/{id}` 读取。新“我的物品”页在服务端按会话分页，展示待审/驳回、最近理由、空结果及错误重试；公开详情不自动回退本人接口。延迟响应在会话替换后丢弃，不展示上个账号内容。

完整编辑仍是 `PUT /api/items/{id}`，提交 `{version,title,description,categoryId,conditionLevel,tags,wantedCategoryId,wantedTags,imageUrl?}`；只有这些字段。REJECTED版本1修改成功后为PENDING_REVIEW版本2；再次决定必须使用2。reviewedVersion可能仍是上次被驳回的0，因此界面必须写“最近一次决定”，不能将它解释为当前复审已处理。400保留输入；401重新登录；403/404停止写；409保留输入并回读，要求重新核对。D的完整编辑/下架表单仍待接入，本批提供并验证这些后端能力，不用本地假数据模拟成功。

## B 与收藏影响

旧 wanted 匹配和 independent-v2 适配均复用 AVAILABLE、ACTIVE用户、无占用筛选；待审、驳回与编辑复审不入候选，推荐算法未改。已有需求关联保留但 offerable=false；新关联待审/驳回目标409。收藏关系保留，待审/驳回显示 itemVisible=false、item=null，total仍计入本人关系。公开 ItemView 的审核理由、操作者、决定、时间与被审核版本为null；仅当前本人/ADMIN能读这些私有字段。

## 本次实际验证（2026-09-08）

- `node scripts/run.mjs test`：用户端原32项与后端119项通过；新增本人详情读测试4项另经 `npm test` 执行，用户端现36项通过。后端42项公共模块、77项API/集成（含8项审核集成及2项严格DTO）。后端接口通过MockMvc进入真实认证、事务、Mapper；H2结果不替代MySQL。
- `node scripts/mysql-test.mjs`：一次性 MySQL 8.4.11、V1–V7，119项全部通过；双ADMIN、编辑/审批竞争各仅一成功、一409；版本与审计一致，越权、非法状态、占用/交接引用、收藏占位、两候选入口、数据库外键/唯一/检查约束均覆盖。容器结束移除。
- `node scripts/mysql-test.mjs --item-review-upgrade`：独立全新MySQL执行1项V6→V7升级检查；逐行核对六种旧状态内容/版本、占用不变，历史公开三种状态正确标记且审计为空；默认待审、演示直发及重复初始化不覆盖验证通过。此命令加入CI；遇到非空schema直接失败，不清库。
- 管理端6项测试及Vite构建、用户端H5/微信构建通过；微信产物6项检查、repository-check与diff检查通过。构建仍有原有管理端分包体积提醒，Flyway仍有MySQL8.4版本支持提醒，未为此扩展依赖升级。
- 浏览器连接另一隔离MySQL与真实后端：C空理由阻止提交、真实驳回及列表回读；并发编辑后真实409、理由/旧版本保留、提交禁用、显式回读版本1后批准；D本人列表/详情显示驳回理由，实际发布跳到本人待审详情并禁用推荐。手机宽度下待审详情内容可读。最后直接SQL回查确认三个测试物品分别为 REJECTED/v1、AVAILABLE/v2、PENDING_REVIEW/v0，6条提交/审核审计与页面操作一致。登录由仅本地测试网关代填进程内随机凭据，业务请求/会话/持久化均来自真实后端，此项不宣称验证真实校园身份登录。

成员环境联调和C/D/B消费复核、微信开发者工具/真机、完整D编辑下架表单尚未完成；浏览器未穷尽所有深浅色/键盘/弱网分支。本批不实现HIDDEN重新上架、正式交换、举报或履历审核。PR不自动合并，需其他成员审核与CI成功。
