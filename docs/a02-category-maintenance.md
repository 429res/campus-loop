# A-02 管理员分类维护接入说明

本批供 C-01 使用，基于已合入 main 的 B-01 独立需求、A-02 本人物品与收藏。分类保持一级目录；新增 Flyway V6，不改 V1–V5。实现前同步 [Issue #20](https://github.com/429res/campus-loop/issues/20)，C/D/B 的消费确认未收到，不把已同步写成已确认。

## API 与字段

全部返回现有 ResultVo；成功 HTTP200，错误 HTTP 状态与 code 一致。管理路径 `/api/admin/categories` 由现有 AuthInterceptor 验证有效数据库会话与 ADMIN，普通用户403、未登录401。

| 调用 | 输入 | data |
| --- | --- | --- |
| GET /api/categories | includeInactive 默认 false；true 用于历史浏览 | Category 数组，保留 id/name |
| GET /api/admin/categories | page=1、size=12、keyword、status | PageResult，默认含全部状态 |
| GET /api/admin/categories/{id} | 正整数ID | Category，404表示已删除/不存在 |
| POST /api/admin/categories | name，sortOrder可省略 | 持久化 Category，ACTIVE/version=0 |
| PATCH /api/admin/categories/{id} | version + name/sortOrder/status 中至少一个 | 持久化 Category，version+1 |
| DELETE /api/admin/categories/{id}?version=0 | 正整数ID、非负整数version | null，仅无引用时删除 |

Category 只有 `id,name,status,sortOrder,version`。名称 trim 后1–64字符且非空白，大小写归一后唯一；其他等价性按数据库排序规则，停用分类也占用名称。内部 name_key 不返回。排序为整数0–9999（新增默认0），status 为 ACTIVE/INACTIVE；PATCH省略保持、显式null拒绝、未知字段拒绝，不接受 owner/id/parentId/name_key。version/sortOrder不接受JSON字符串、小数或溢出。相同值 PATCH 仍 version+1。

```json
{"name":"学习工具","sortOrder":20}
```

```json
{"version":0,"name":"学习与工具","sortOrder":10,"status":"INACTIVE"}
```

分类数组与分页统一按 sortOrder/id 升序。分页 page≥1、size1–100；keyword最长100；status可省略、空串、ACTIVE、INACTIVE。total 为过滤后的分类数，不是物品数；空结果 records=[]/total=0，超出末页 records=[]但保留实际total。

## 状态与引用

| 场景 | 规则 |
| --- | --- |
| 现有分类迁移 | 保留id/name，ACTIVE、sortOrder=0、version=0，因此原始顺序不变 |
| 停用 / 重新启用 | PATCH status，均需要当前version；不修改引用记录或交换状态 |
| 物品发布 / 完整编辑 | categoryId 和 wantedCategoryId 都必须 ACTIVE，不存在或停用400；整次不写入 |
| 需求新增 / 字段编辑 | 最终 categoryId 必须 ACTIVE；可以改选可用分类再保存，不能仅改正文绕过 |
| 需求切换 ACTIVE | 同时复核分类和现有候选物品；停用分类400 |
| 需求切换 INACTIVE / 逻辑删除 | 分类停用不妨碍这些退出操作 |
| 物品下架 | 继续按 AVAILABLE、version、占用与进行中交换保护，不要求分类ACTIVE |
| 删除未引用分类 | 可删除 ACTIVE 或 INACTIVE，成功data=null，再次404 |
| 删除引用中分类 | 409；物品 category_id/wanted_category_id、需求 category_id，包含全部状态与 DELETED墓碑 |
| 历史解释 | 物品/需求保留 categoryId，读取当前 categoryName；停用不隐藏内容，改名影响当前显示，不保存名称快照 |

分类停用是目录选择限制；已有 AVAILABLE 物品、ACTIVE 需求仍按原推荐规则参与，不是撤回或审核。未扩展推荐算法、无限层级、内容审核、正式交换、物品重新上架、分类合并或批量搬迁引用。物品和需求历史不会被分类接口删除或清空。

## 并发与迁移

V6 增加 status/sort_order/version、内部规范化 name_key 唯一约束、状态/排序/版本 CHECK 和分类排序索引。现有物品及需求外键保持非级联约束；历史迁移不改。Java 的 trim/Locale.ROOT 小写处理写入名称键，迁移用 LOWER(TRIM(name)) 回填现有种子。

PATCH/DELETE 锁分类行、检查version；PATCH按id/version更新并回读。业务选择分类时在自身事务内按分类ID升序加锁，保持到业务写入提交。需求先锁需求，再锁已有物品及占用，最后锁分类；物品编辑先锁物品及占用，再锁分类。分类删除的引用检查是 READ_COMMITTED 非锁定读，不反向锁引用记录，避免 category→item 与 item→category 的锁顺序冲突。最终外键防止任何悬空引用。

分类停用/删除先提交：等待中的业务必须读取新状态并400。业务先提交：删除读取到引用并409。分类修改并发只允许一份版本成功，另一份409；与删除竞争，删除先成功则更新404。名称唯一键让重复/并发创建至多一条成功，409不留下部分状态更新。

## C / D / B 接入

C：列表可切换到管理分页；记录保存id/version用于编辑、停用、启用和删除。已有 CategoryFormDialog 只输出 `{name}`，接入回调的第二参数为原分类，编辑必须在回调附加其version；新增可省略sortOrder。成功后回读列表/单条，409保留表单并回读，让管理员重新决定，不自动用新version覆盖。400显示msg，401走既有会话恢复，403停止写入，404提示记录已不存在。重名、版本冲突、引用保护都是409，以服务端msg说明原因；UI不能依赖解析msg执行分支。

本批将管理分类目录与物品筛选读取改为 includeInactive=true，展示分类状态/顺序；C现有正式写按钮继续禁用，等待C完成表单、状态动作和删除确认接线。组件夹具不是持久化能力或联调证据，没有将C-01整体标成完成。

D：发布和需求选择继续 GET /api/categories 默认 ACTIVE，按返回顺序渲染；不要传 includeInactive=true 到新业务选择器。失效分类的草稿保留正文并要求重选，分类全停用返回[]也要清除旧ID，本批修正了发布页这个空数组边界。首页用于浏览历史物品的分类筛选改为 includeInactive=true。已打开的旧表单可能收到400，保留输入并刷新分类；没有刷新也由服务端拒绝停用目标。需求编辑的原分类不在选项中时应显示未选择，不能静默改用第一个分类；启用需求失败可先编辑改选或保持停用。

B：B-01已经合入，DemandService复用与物品相同的事务分类可用性校验；所有需求引用（含墓碑）保护分类删除。原需求版本、候选集合基数和所有权规则不变。B-02匹配读取不增加分类状态过滤，未实现的交换写入在落地时仍必须遵循统一状态机与锁顺序，不得把目录状态当作物品可交换性。

## 验证

2026-09-08 本批实际执行：

- 根 `node scripts/run.mjs test`：用户端27项；后端109项（公共模块42、API67），失败/错误/跳过均0，其中新增分类集成测试12项。
- `node scripts/mysql-test.mjs`：一次性 MySQL 8.4.11，V1–V6成功执行，后端109项全部通过，包括ADMIN/普通用户、非法输入、大小写重名、DB回读、版本竞争、历史三类引用、并发业务创建/分类删除、等待停用提交后复核，以及需求/物品/分类锁顺序。容器完成后自动移除，没有连接日常库。
- MySQL首轮有1项测试断言失败：数据库正确拒绝缺失名称键，但Spring异常子类型与H2不同。改用跨数据库数据访问异常断言，并增加数据未变回读，完整重跑通过；没有把该次失败记作通过。
- 管理端 `npm test` 6项、`npm run build` 通过；用户端 H5、mp-weixin 构建通过，`npm run test:mp-build` 6项通过。管理端已有大chunk提示、Flyway对MySQL8.4的版本提示仍存在，未升级依赖。
- 实际浏览器使用本地已构建页面与虚构HTTP夹具：管理目录展示排序/停用，筛选停用分类；管理物品与用户首页均发出 includeInactive=true，并可按停用分类ID筛选。发布只显示ACTIVE选项；建立草稿后将夹具切为全部停用，刷新保留正文、清空两个分类、禁用分类选择，提交被必填校验阻止。H5在390px窄屏查看提示与选择器，管理端在桌面查看表格。夹具不连接MySQL、不实现分类写入，不作为持久化或真实端到端联调证据。
- `node scripts/repository-check.mjs`、`git diff --check` 通过。

数据库测试通过MockMvc调用实际控制器、事务服务和真实MySQL，并执行直接约束SQL；未启动独立后端HTTP进程做C/D页面与MySQL联调。没有微信开发者工具/真机或本批减少动态效果实机检查，没有完成C正式维护表单与D需求页面接线。后续由消费方审核和联调，合并仍需另一成员审核与CI成功，不自动合并。
