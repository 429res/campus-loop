# B-05.2 参与者独立确认与来源链

## 已确认规则与协作

用户已确认：仅关联 B-04 已完成交换的 SELF_REPORTED（REPAIR/TRANSFER）链尾，由原作者发起确认；从实际交换固定取全部 2/3 名参与者，作者也须独立确认。发起请求明确将该事件证据快照授权全体参与者；这是对 B-05.1 三方另一人证据不可见规则的显式扩展，仅限已发起的该事件，不扩散到修正或其他旧履历。确认表示本人认可这份声明和证据，不默认见证维修，不是管理员核验。

修正/撤回停止旧内容的新确认；旧内容、证据、每人确认时间和已形成的来源事实保留。已全员确认的可信度只属于原事件，不能套用到修正。撤回不删除已做声明及历史授权；已授权参与者仍可核对历史快照。每个事件只发起一次，撤回后如需重新提交应追加修正，再明确授权和独立确认。没有日历超时；“过期内容”指已修正/撤回或与快照不一致。

分支已快进包含 B-05.1 锁序修正 PR #39 的 main 合并9359522；通过 Issue #40 与 A/C/D 同步追加 V13，不改 V1–V12。C/D 消费下面字段及来源链，管理员读取沿用 ADMIN 角色授权，不增加代确认/核验操作。

## 契约（已实现）

- POST `/api/items/{itemId}/history/{eventId}/confirmation-request`：仅作者，请求严格为 `{ "shareEvidenceWithAllParticipants": true }`。服务端生成不可变内容/证据 SHA-256 快照、规则 `history-confirmation-v1` 和真实参与者集合；重复发起返回同一请求，不自动确认作者。
- POST `…/confirm`：真实集合中的登录参与者，请求严格为 `{ "snapshotHash": "64位小写SHA-256", "acknowledged": true }`。针对同一事件快照每人最多一条，重复幂等；新确认不接受客户端交换ID、作者、名单或可信度。
- POST `…/withdraw-confirmation`：仅原作者，请求 `{ "snapshotHash": "…", "reason": "1–500字" }`；追加撤回记录，同内容重试幂等，不同原因409。无删除/重开/覆盖入口。
- 读取复用原分页/详情。`recordedEvidenceLevel` 保留原存储来源；`evidenceLevel` 仅在确有全员确认时对该具体旧事件展示 BOTH_CONFIRMED。`confirmation` 给出模式、状态、confirmedCount/requiredCount、请求/完成/撤回时间、是否当前链尾。参与明细、snapshotHash/快照、撤回原因及允许动作仅作者/已授权参与者/ADMIN可见；公开只有计数、状态及来源时间，无名单、证据标识或交换ID。
- B-04 EXCHANGED/BOTH_CONFIRMED 复用实际全体参与者的交出/接收时间和流向，模式 B04_HANDOFF，不插入新的确认请求或确认行，不要求重复确认。旧记录缺少实际依据时明确 UNAVAILABLE，不伪造确认人数。

400越权字段/类型/声明格式；401会话无效；403代作者/非参与者写入；404不可见记录；409旧内容/哈希不符/无有效交换依据/撤回冲突。D在409后刷新详情及correctedByEventId，清除旧确认勾选，不能把旧快照哈希自动套到修正事件。

## 事务与追加约束

复用 A/B ExchangeTransactionExecutor、数据库 UTC 和共同锁序：关联交换 → 全体相关用户升序 → 物品。修正使用相同交换/物品锁，确认在锁后重读链尾和撤回；并发最后确认按此串行。请求、快照及全体成员同事务追加；确认和撤回只 INSERT，无原履历 UPDATE。主键限制每事件每参与者一次，复合外键绑定确认对象/哈希及真实交换参与者。全部确认时间由持久确认行计算，不另存第二个可漂移可信度字段。

## 验证和切片状态

- `node scripts/run.mjs test`：用户端单测49通过；后端公共72通过，API142项中122通过、20项MySQL专用明确跳过；后端实际194通过，无失败。新工作树最初缺少Vue依赖导致命令停止，按锁文件npm ci后已完整重跑，不将该环境失败计为通过。
- `CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs`：全新MySQL8.4.11迁移V1–V13，后端214项全部通过，无跳过；共用交换套件61项，其中本片新增7项。覆盖2/3人全员确认、B-04事实复用、身份/名单/快照伪造拒绝、三方证据授权、原来源和修正链、请求/成员/确认/撤回各写阶段真实SQL约束失败完整回滚、外键保护；真实两连接覆盖最后确认、重复发起、修正/确认及撤回/确认的双向提交顺序。
- 首轮MySQL发现加锁前MyBatis缓存导致旧内容确认；独立锁定重读修复后完整重跑214项通过，保留发现问题的竞态回归。H2结果不能证明该并发边界。
- `CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs --item-review-upgrade`：另一个全新MySQL，1项通过。单独从V12升级V13，逐字段保留已有履历/时间/证据，四张新表为空，不制造全员授权或确认。既有V6起其他升级回归一并保留。
- 临时容器与虚构2×2图片已清理；没有连接成员日常库或输出凭据。既有Flyway对MySQL8.4支持范围提示保留，不夹带依赖升级。无前端页面改动，未执行浏览器、微信真机或C/D页面联调。

实现与隔离MySQL验证已完成，待PR审核。本片不包含管理员核验、C/D页面联调。所有测试使用隔离虚构数据，不将夹具送入正常业务。

## 来源转换与权限矩阵

| 对象/状态 | 显示来源 | 可追加操作 | 确认的有效范围 |
| --- | --- | --- | --- |
| 合法自述链尾，未请求 | SELF_REPORTED | 作者发起、追加修正 | 无参与者确认，0/0表示未建立集合 |
| 已请求，0..N-1/N | SELF_REPORTED | 集合中未确认者确认；作者撤回、修正 | 仅本事件和snapshotHash |
| N/N | BOTH_CONFIRMED，recordedEvidenceLevel仍SELF_REPORTED | 作者可撤回或修正；同人确认重试幂等 | 全员认可旧声明，非管理员核验 |
| 已修正 SUPERSEDED / 已撤回 WITHDRAWN | 未全员仍SELF_REPORTED；已全员保留该旧事件BOTH_CONFIRMED | 拒绝新确认；精确撤回重试不重复追加 | 历史事实保留，不能表示新修正已确认 |
| B-04真实完成事件 | BOTH_CONFIRMED，模式B04_HANDOFF | 无新确认/撤回入口 | 复用真实2N项交出/收到声明 |
| 旧事实缺少完整B-04依据 | 保留原存储来源，confirmation=UNAVAILABLE | 不发起、不补造确认 | 0/0不可解释为已完成确认 |
| ADMIN_VERIFIED | 本片不能产生 | 管理员只有授权读取，无代确认/核验 | 后续管理员核验切片 |

作者不能排除自己或其他成员；停用账号不能新确认，已记录声明不会因账号后来停用而被改写。新确认不使用不断递增的全局计数version，而使用不可变eventId＋snapshotHash；成员行唯一约束实现幂等。全部确认时间取最后一条真实确认的数据库UTC时间，顶层confirmedAt与confirmation.completedAt一致，原始cl_item_history.confirmed_at仍不改写。

| 读取身份 | 基础来源/计数/状态 | 请求快照与参与明细 | 私有证据 |
| --- | --- | --- | --- |
| 匿名/无关者 | 仅公开物品，可读；无身份ID/名单 | null | 不可读 |
| 原作者 | 可读本人事件 | 可读 | 可读 |
| 未发起请求的三方另一人 | 沿用B-05.1原范围 | 不因此取得旧自述材料 | 不可读 |
| 已明确授权的全部交换参与者 | 可读该事件，包括非公开物品 | 可读该请求快照和各人确认时间 | 仅该事件；不扩展到新修正 |
| ADMIN | 可读 | 可读供后续核验 | 可读已关联材料，不能冒充参与者 |

快照包括ruleVersion、eventId/itemId、authorId/exchangeId、exchangeHistoryEventId（关联的原B-04事实ID）、声明、发生/记录时间、原来源、correctsEventId、全体participantIds以及每张私有PNG的uploadId/媒体类型/sha256。snapshotHash绑定整个序列化快照；每次新确认复核内容及文件摘要。图片没有覆盖/删除API，旧事件及证据引用继续保留。被授权的快照和历史材料不会因撤回请求静默消失。

## D确认状态与冲突恢复样例

以下为虚构响应片段。真实响应使用原ResultVo，原时间线分页与排序不变；样例不写入业务库。

三方仅两人确认（不能显示“双方确认完成”）：

```json
{
  "id":701,"eventType":"REPAIR","statement":"自述更换零件",
  "recordedEvidenceLevel":"SELF_REPORTED","evidenceLevel":"SELF_REPORTED","confirmedAt":null,
  "confirmation":{
    "mode":"SELF_REPORT","status":"PENDING","confirmedCount":2,"requiredCount":3,"currentContent":true,
    "participants":[
      {"userId":101,"displayName":"虚构甲","confirmedAt":"2026-09-08T09:00:01Z"},
      {"userId":102,"displayName":"虚构乙","confirmedAt":"2026-09-08T09:00:02Z"},
      {"userId":103,"displayName":"虚构丙","confirmedAt":null}
    ],"allowedActions":["CONFIRM"]
  }
}
```

D展示“已确认2/3人”，用GET实际返回的snapshotHash提交，不用样例哈希。第三人独立确认后返回3/3、COMPLETE、evidenceLevel=BOTH_CONFIRMED，completedAt/confirmedAt是第三次实际提交时间；双方同理1/2→2/2。作者在发起授权时没有隐式确认；按钮文案明确“我认可此事件声明及证据”，不能写“平台已核验”。

若响应409，保留用户说明，刷新GET事件：SUPERSEDED时链接correctedByEventId的新内容并清除旧勾选；WITHDRAWN时停止新确认；哈希冲突时重新展示服务端快照，不能自动重试确认新内容。请求超时可对同一事件、同一hash重试，原成员确认时间不变。公开响应participants/snapshot/snapshotHash/withdrawalReason均null，allowedActions为空。

## C可核验来源链

C按“原B-04事实ID → 自述eventId → 不可变请求快照 → N条参与者确认记录”读取真实依据，再沿correctedByEventId查看后续修正。新修正有独立ID和哈希、独立请求及确认集；不能把旧701的3/3转给新703。发生时间未知仍为null，记录时间/requestedAt/每人confirmedAt分别展示。撤回时间和原因保留，但不改原声明、确认记录或B-04事件。C取得来源材料不等于已执行ADMIN_VERIFIED，核验操作者/结果写入本片未实现。
