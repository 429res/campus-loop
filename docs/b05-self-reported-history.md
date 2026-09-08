# B-05.1 自述、私有证据与查询

用户已确认：当前所有者仅自述本人持有期间维修/流转；曾经所有者须有B-04已完成交换证明，仅声明其持有期间。已知发生时间须在该期间且不晚于记录时间；未知须明确timeUnknown=true和occurredAt=null。自述不改变所有权，只能SELF_REPORTED，作者与记录时间由服务端决定。修正仅原作者追加关联事件，原记录不覆盖。首次所有者的持有起点未记录时不伪造下界；这仍是自述，不代表平台证明其维修确实发生。

## A/C/D契约同步

B-04 PR #36提交185aec2为事实基线，现已合入main（a7cdba5）；本功能分支已快进包含该合并；本片追加V12，V1–V11不改。复用cl_item_history、B-04真实EXCHANGED/BOTH_CONFIRMED事实、统一认证/分页/异常、数据库UTC及本地图片解码再编码。A请核对V12序号与锁序，C/D消费字段和隐私投影；具体关键规则已由用户决定，成员审核仍需PR完成。

- GET /api/items/{id}/history?page=1&size=12：公开物品可匿名查询；支持有效登录的可选认证。GET /api/items/{id}/history/{eventId}读取单条。按recordedAt/id降序，page≥1、size1–100；非法或重复查询参数400。公开物品沿用AVAILABLE/RESERVED/EXCHANGED；非公开物品仅本人/ADMIN可看全部基础履历，其他作者或该件物品关联交换交出/接收双方仅看自己有关系的事件，列表total同样过滤。无授权记录404。
- POST /api/items/{id}/history：严格字段eventType(REPAIR/TRANSFER)、statement(1–2000字)、occurredAt(UTC ISO8601或null)、timeUnknown(boolean)、relatedExchangeId(正整数或null)、correctsEventId(正整数或null)、evidenceUploadIds(0–5个不同私有上传ID)。字段必填，禁止author/owner/sourceLevel/evidenceLevel/recordedAt/status等越权字段。relatedExchangeId仅可引用该物品本人参与的B-04已完成交换；曾经所有者须选择其交出物品的交换，当前所有者可不选或选择其最近接收交换。已知持有起点从B-04接收事实推导，未记录的初始持有起点保持未知，不把物品创建时间冒充取得所有权时间；发生时间最早支持1970-01-01T00:00:01Z、精确到秒，晚于数据库当前时间拒绝；修正沿用原事件的时间窗口及交换关联，不能扩大声明范围。内部ownership_started_at/ownership_ended_at是该次声明可覆盖的时间边界：当前持有者的上界取提交时数据库UTC，不表示物品已经交出；没有已知取得事实则下界null。旧SELF_REPORTED行没有这组授权依据时只读，不补造历史授权。
- 修正correctsEventId仅指向本人同一物品的SELF_REPORTED事件；一条事件至多有一个直接修正，新修正应接到链尾，否则409。不允许修改BOTH_CONFIRMED/ADMIN_VERIFIED，也无PUT/PATCH/DELETE覆盖入口。新自述POST不承诺请求幂等，提交结果不明先刷新；修正重复提交以409保护单一修正链。
- POST /api/uploads/evidence：复用现有multipart file及PNG/JPEG/GIF、5MB/1600万像素限制，重编码PNG，返回uploadId；文件存于公开uploads目录以外，不提供静态URL。GET /api/history-evidence/{uploadId}：作者可预览本人上传；引用后仅作者、该事件关联交换中该件物品的交出/接收双方及ADMIN可读。三方环的另一人不自动拥有此物品证据权限；新所有者不因接手自动获得旧私有材料。现有POST /uploads及公开物品图路径保持原语义，不允许把旧公开上传当私有证据。

查询保留id/itemId/eventType/statement/evidenceLevel/authorDisplayName/occurredAt/timeUnknown/recordedAt/correctsEventId/correctedByEventId/confirmedAt/verifiedAt。仅授权的作者、该件交换双方及ADMIN返回authorId/relatedExchangeId/evidence引用，其余这些字段为null；不返回证据计数。本人SELF_REPORTED链尾canCorrect=true。证据为image/png，读取private/no-store并禁止浏览器类型嗅探。已授权声明正文是公开自述，不应填写私人联系信息；敏感图片只能走私有证据入口。

400字段/时间/证据引用非法，401无效会话，403无作者权限，404记录/证据不可见，409修正链或所有权期间冲突。未完成交换不能当持有证明。记录时间来自数据库UTC；修正的新记录有新recordedAt，未知发生时间不伪造为创建时间。

## 验证与边界

实现与本地验证已完成，待PR审核：

- `node scripts/run.mjs test`：用户端49通过；后端公共规则72通过，API134项中118通过、16项MySQL专用明确跳过；后端实际190通过，无失败。最终增加的非公开时间线逐行/total隐私及真实来源外键断言，通过Maven Wrapper针对相关测试另跑1项验证，不重复计入总数。
- `CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs`：全新MySQL8.4.11、Flyway V1–V12；后端206项全部通过，无跳过。共用ExchangeDomainIntegrationTest 53项（B-05新增7项），复用真实B-04创建/确认/交接与A的SQL探针/竞态工具，不复制交换实现。
- MySQL覆盖保存回读、未知/已知时间、伪造来源/身份/外人上传拒绝、旧公开上传用途限制、真实三方完成后曾经所有者授权、原文与修正链、图片无静态泄漏及作者/物品双方/第三人/管理员可见性。SQL外键/唯一约束拒绝外人修正、修正分叉、重复引用及删除被引用上传；append/evidence两个写阶段注入真实SQL约束失败，逐字段比对全部相关业务行保持不变。真实两连接验证同链修正一成功一409、最后流转后等待中的旧owner新声明403。
- `CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs --item-review-upgrade`：另一个全新MySQL，1项通过；V11→V12保留旧履历来源/作者/发生和记录时间、旧公开上传URL/owner，并保留既有交换事实/占用，不伪造新证据或授权窗口。
- 两个临时数据库容器自动移除；虚构2×2图片在系统临时目录创建并清理，没有连接成员日常库、输出凭据或保存真实证据。保留既有Flyway对MySQL8.4支持范围提示，不引入依赖升级。

仅本片自述/查询/证据；参与者追加确认、管理员核验、争议裁决均未实现。B-04自动交换履历继续原始原子入口，不用自述API伪造真实交换。

## C/D时间线与私有材料样例

以下为虚构响应形状，非真实账号/证据，不在正常业务中种入已完成交换。API返回ResultVo，分页data为`{records,total,page,size}`；时间线按记录时间而非未知的发生时间排序。同一次GET使用一致读取快照，不改变任何业务状态。

```json
{
  "eventType":"REPAIR","statement":"自述：曾更换拉链，具体日期不详",
  "occurredAt":null,"timeUnknown":true,"relatedExchangeId":null,
  "correctsEventId":null,"evidenceUploadIds":[]
}
```

匿名查看公开物品501的GET /api/items/501/history?page=1&size=12：

```json
{
  "records":[
    {"id":703,"itemId":501,"eventType":"REPAIR","statement":"修正：更换的是拉链头","evidenceLevel":"SELF_REPORTED","authorDisplayName":"虚构同学甲","occurredAt":null,"timeUnknown":true,"recordedAt":"2026-09-08T10:00:00Z","correctsEventId":701,"correctedByEventId":null,"confirmedAt":null,"verifiedAt":null,"authorId":null,"relatedExchangeId":null,"evidence":null,"canCorrect":false},
    {"id":702,"itemId":501,"eventType":"EXCHANGED","statement":"交换参与者分别确认交出与收到","evidenceLevel":"BOTH_CONFIRMED","authorDisplayName":"虚构同学甲","occurredAt":"2026-09-08T09:00:00Z","timeUnknown":false,"recordedAt":"2026-09-08T09:00:00Z","correctsEventId":null,"correctedByEventId":null,"confirmedAt":"2026-09-08T09:00:00Z","verifiedAt":null,"authorId":null,"relatedExchangeId":null,"evidence":null,"canCorrect":false},
    {"id":701,"itemId":501,"eventType":"REPAIR","statement":"自述：曾更换拉链，具体日期不详","evidenceLevel":"SELF_REPORTED","authorDisplayName":"虚构同学甲","occurredAt":null,"timeUnknown":true,"recordedAt":"2026-09-08T08:00:00Z","correctsEventId":null,"correctedByEventId":703,"confirmedAt":null,"verifiedAt":null,"authorId":null,"relatedExchangeId":null,"evidence":null,"canCorrect":false}
  ],"total":3,"page":1,"size":12
}
```

702必须来自B-04真实完成事务，绝不从本轮POST提交EXCHANGED。`ADMIN_VERIFIED`保留既有来源枚举，但本片不能产生这种记录；若后续核验已有持久记录，查询忠实返回来源和verifiedAt。本轮不制作假核验演示事件。

作者查看701时authorId为本人ID；若确有私有证据，evidence为`[{uploadId:"11111111-1111-1111-1111-111111111111",url:"/api/history-evidence/11111111-1111-1111-1111-111111111111",mediaType:"image/png"}]`，而匿名/无关用户是null且无证据数量。原文和correctedByEventId仍可见，不能把701替换成703；703本人canCorrect=true，下一次修正指向703。B-04事实和非本人自述canCorrect=false。

| 查看者 | 公开物品基础时间线 | 非公开物品基础时间线 | 私有证据及交换关联 | 写入边界 |
| --- | --- | --- | --- | --- |
| 匿名/无关登录用户 | 可读，证据/身份ID/交换ID为空 | 404 | 不可读 | 非法作者拒绝 |
| 自述作者 | 可读 | 可读本人及其关联事件 | 本人事件可读，未引用本人上传可预览 | 本人SELF_REPORTED链尾可追加修正 |
| 关联交换的该件物品交出/接收双方 | 可读 | 仅相关事件 | 可读该事件材料，不能看无关联的旧材料 | 不可代作者修正 |
| 新当前所有者 | 可读 | 可读全部基础履历 | 不自动继承旧私有材料，仍逐事件授权 | 可自述本次持有期间 |
| 三方交换另一人 | 可读公开基础字段 | 无其他关系则404 | 不因同环自动授权 | 不可代声明 |
| ADMIN | 可读 | 可读全部基础履历 | 可读已关联事件证据；无权预览他人未引用上传 | 没有代作者修改/参与者确认/核验入口 |

D将unknown显示为“发生日期未知”，recordedAt显示“记录于”，不能把记录时间当维修日期；TRANSFER标为自述、不表示平台转移owner。公开statement用于展示，不存放私人联系方式，敏感图片只用私有证据。C/D查看材料使用现有会话注入Authorization获取PNG二进制（不套JSON ResultVo解包），H5可用blob、微信可用带请求头的下载适配；不能将受保护URL改成公开静态路径或加入token查询参数。JSON时间线与图片均private/no-store，按Authorization区分响应。

400保持表单并提示字段/时间/引用错误；401重新登录；403不能代作者，404不泄露隐藏记录；409先刷新链尾/所有权，不自动把旧声明套到新的持有期间。修正失败保留原记录，空分页返回records=[]及同权限total。POST新自述没有幂等键，网络结果不明先查询，不自动反复新增。

## 数据与事务约束

V12允许occurred_at为NULL，不改B-04已有时间/来源；追加修正关系及授权时间窗口。复合外键绑定原事件ID、物品、作者、来源，CHECK只允许SELF_REPORTED作为修正，单一corrects_event_id唯一约束禁止修正分叉；证据表外键限制悬空引用与删除已被引用的上传。HistoryMapper仅提供追加和查询，无UPDATE/DELETE历史方法；HTTP覆盖方法405，原始字段不更新。应用权限与这些关系约束共同实现追加式履历，不把数据库维护人员的直接SQL权限当作用户API能力。

写入复用ExchangeTransactionExecutor的READ_COMMITTED整事务重试，用户锁→物品锁；无反向exchange锁，不改物品version/owner或交换/需求/占用。物品锁稳定真实完成流转及修正链，数据库UTC在取得锁后读取。B-04最后确认与自述竞争时按共同锁序串行并重读所有权，不能凭等待前的身份写入。私有上传的owner/visibility无修改入口；新引用复用UploadReferenceService归属和用途检查，文件缺失拒绝；事件与全部证据引用同事务写入，失败全部回滚。

本地证据目录为UPLOAD_DIR的同级`<目录名>-evidence`，不在任何静态ResourceHandler下。运维备份须同时包含两目录和数据库；不启用OSS、文件删除、PDF/文档证据或公开分享链接。本片不为孤立上传做自动清理，延续现有上传清理待开发边界。

关联交换的counterparty仅表示该件物品流向另一方和证据授权范围，不表示对方见证维修或确认了自述；SELF_REPORTED的confirmedAt/verifiedAt均为空。

无页面改动；本轮未执行浏览器、微信真机或C/D页面联调。来源确认与管理员核验明确待后续，不标记整个B-05完成。仓库检查和文档链接检查见PR证据。
