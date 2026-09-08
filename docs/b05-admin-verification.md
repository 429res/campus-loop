# B-05.3 管理员事件核验（已实现，待PR审核）

## 当前依赖与范围

B-05.1 PR #38/#39、B-05.2 PR #41均已合入；本片从main 2aa43c6建立feature/b05-admin-verification。前两片保留原SELF_REPORTED、B-04真实BOTH_CONFIRMED、事件证据引用、修正链及不可变参与者确认快照。基线时ADMIN_VERIFIED无写入口；本片新增独立来源审计。已有AuthInterceptor对/api/admin/**执行ADMIN校验；核验事务还须锁后重新确认管理员仍可用，不能仅依赖请求进入时角色。

本片只核验一个事件的一份内容/证据版本，不变更owner、交换、交接、需求或占用，不裁决举报/争议。原始履历和参与者确认不覆盖；决定追加独立审计来源，原事件和新修正分别显示来源。

## 用户已确认的关键业务规则

用户已明确采用以下规则，本片按这些规则接通生产入口：

- 只核验链尾具体声明；REPAIR/TRANSFER通过须至少一份可读私有证据，B-04 EXCHANGED可使用完整交接来源链；不强制先取得参与者补充确认。
- ADMIN填写公开范围摘要及私有详细理由，结论限该范围的材料是否支持声明，不表示整个物品历史均真实。
- 不核验本人声明或本人参与交换；一事件一次终局决定，已决定不覆盖。重复同键同内容返回原决定；新修正成为新待核验对象，不继承旧级别。

队列包含待核验、已决定和已修正记录；缺少通过证据仍可驳回，不能通过。

## 实现调用方案

读取提供管理员分页队列和单事件详情，复用ResultVo/PageResult及现有私有证据读取；公开来源展示只提供安全范围摘要/结论/时间，详细理由、快照、私人参与者信息逐字段授权。修正链非尾只供追溯，不产生新的决定。

写入复用ExchangeTransactionExecutor和数据库UTC，保持可选exchange→操作管理员用户→item→事件锁序；事件锁查询不联表锁作者，并使用独立锁后查询避免B-05.2已验证的MyBatis一级缓存问题。管理版本/状态用条件更新；审计INSERT与条件更新同事务，失败全回滚。历史快照和原来源保留，读取不创建待办或隐式写行。

A协调V14迁移序号（已通过Issue #42同步，当前main到V13），不修改旧迁移。C消费队列/详情/错误恢复，D消费限定到单事件的核验来源。已确认的具体请求字段与状态矩阵见下文。

## 已完成的独立检查

根AGENTS、当前分支/工作区及前两片实现已核对，根目录已有改动保持不变。2026-09-08在本片独立工作树运行`node scripts/run.mjs test`：用户端49通过；公共后端72通过，API142项中122通过、20项MySQL专用明确跳过，后端合计194通过。此为前两片基线回归，不是本片核验功能验收。以上仅记录原基线；本次实现及验收见下节。

## C/D接口约定（已实现）

GET /api/admin/history-verifications?page=1&size=12&status=PENDING：status可省略，支持PENDING/APPROVED/REJECTED/SUPERSEDED；size1–100，按原记录时间/id倒序，读接口不创建待办。GET /api/admin/history-verifications/{eventId}返回具体声明、原来源、参与者来源链、私有证据快照、snapshotHash、version及允许动作。

POST /api/admin/history-verifications/{eventId}/decision 严格请求字段version(非负整数)、snapshotHash(64位小写hex)、idempotencyKey(1–64字母数字及._:-)、decision(APPROVED/REJECTED)、scope(1–500字公开摘要)、reason(1–2000字私有理由)。幂等范围为管理员ID＋key，比较全部规范化请求内容及事件ID；完全相同返回原决定，同键不同内容409。每事件仅一次决定，version0→1；其他管理员/其他key不能改写。新修正独立version0待核验，旧决定仍可追溯。

通过追加ADMIN_VERIFIED来源记录，驳回追加审计但不降级原参与者事实；原cl_item_history/确认记录不UPDATE。公开verification只含decision/scope/decidedAt及currentContent，禁止reason/snapshotHash/snapshot/核验人ID/key进入公开字段；详细材料仅ADMIN可见。顶层evidenceLevel仅该事件通过后显示ADMIN_VERIFIED，recordedEvidenceLevel和confirmation继续保留。

401会话/账号失效，403非ADMIN或自身利益冲突，404不存在，400非法字段，409版本/快照/状态/幂等冲突。C在409刷新详情，重新展示材料并要求人工重新选择，不自动把旧决定套入新快照。核验不重新上架、不修改owner、不替交接、也不裁决争议。

## 实现与来源/权限矩阵

V14独立保存状态守卫和追加审计。原事件没有版本时管理视图返回虚拟PENDING/version0；读队列不INSERT。决定事务插入守卫，再条件更新version0→1并追加唯一审计；任一阶段失败全部回滚。每事件只有一个终局审计，原声明、证据引用及参与者确认均保留。APPROVED审计是该事件新增的ADMIN_VERIFIED来源记录，不表示原始来源被改写。

| 对象 | 允许动作 | 结果与边界 |
| --- | --- | --- |
| 待核验链尾，有合格证据 | 无利益冲突的有效ADMIN通过或驳回 | 一次决定，version变为1 |
| 待核验链尾，材料不足/不可读 | ADMIN仅驳回 | 保留原声明/来源，不能凭按钮绕过服务端证据检查 |
| 已APPROVED/REJECTED | 精确同键请求重试 | 原审计不变；其他key/管理员/内容409 |
| 未决定已被修正 | 只读SUPERSEDED | 核验旧内容409，新的链尾从PENDING/version0开始 |
| 已决定后被修正 | 旧结论仍可追溯，currentContent=false | 新事件不继承ADMIN_VERIFIED |
| 作者或普通用户 | 仅授权时间线读取 | 写入403，不能传管理员ID或来源级别 |
| 声明作者/关联交换参与者恰为ADMIN | 只读 | 禁止决定本人的利益相关事件 |

REPAIR/TRANSFER至少一份满足原上传归属/PRIVATE_EVIDENCE/路径检查且可读的证据才能通过；摘要记录每份引用和文件SHA-256，不可读的材料明确为null摘要。B-04 EXCHANGED通过要求真实完成环及全体交出/接收来源。核验快照包含声明、原始来源、修正关系、发生/记录时间、证据摘要和当时参与者确认链；新增参与者确认或文件变化导致旧snapshotHash冲突。决定后的原审计快照永久保留，后续附加确认不重写旧决定依据。

操作锁序为可选exchange→操作管理员user→item→原事件。核验不需变更作者/参与者，不锁这些用户；锁后事件SELECT不JOIN作者，避免隐式外键/联表用户锁与账号管理形成反向顺序。管理员账号在持锁后再验证role/status。统一ExchangeTransactionExecutor负责READ_COMMITTED及整事务重试，时间来自数据库UTC。

## C详情与冲突恢复样例

虚构形状：GET详情返回eventId/itemId/version/status/content/snapshotHash/verification/allowedActions。content是服务端当前可核对材料，verification.snapshot是已决定时冻结的材料，两者不能混用。相同管理员刷新得到的hash可提交；另一管理员看到同份内容得到相同hash。

```json
{
  "version":0,
  "snapshotHash":"从本次GET复制的64位哈希",
  "idempotencyKey":"fictional-review-001",
  "decision":"APPROVED",
  "scope":"仅核对本事件所述更换零件与所附照片的一致性",
  "reason":"私有：本次查阅材料及判断依据，不能放入公开摘要"
}
```

scope输入框明确标注公开，不能填写私人联系方式或敏感细节。reason与完整快照仅ADMIN可读，原作者或交换参与者也不会自动看到这份私有管理理由。任何敏感图片继续使用既有鉴权二进制证据接口，不提供静态链接；所有核验JSON使用private/no-store。

401重新登录；403关闭决定操作并说明身份或利益冲突；400保留表单修改字段；409刷新version/status/snapshotHash，显示已决定记录或新链尾，要求管理员重新核对，不能自动换hash重发。网络结果未知时保留原key和原请求重试；同键改结论/理由/范围/版本/目标返回409。

## D来源显示样例

公开时间线的单事件片段：

```json
{
  "id":701,"recordedEvidenceLevel":"SELF_REPORTED","evidenceLevel":"ADMIN_VERIFIED",
  "verifiedAt":"2026-09-08T10:00:00Z",
  "verification":{
    "decision":"APPROVED","scope":"仅核对本事件零件说明与所附材料一致性",
    "decidedAt":"2026-09-08T10:00:00Z","currentContent":true,
    "adminId":null,"version":null,"reason":null,"snapshotHash":null,"snapshot":null
  }
}
```

显示“此事件在所列范围内经管理员核验”，不标记“整件物品全部历史真实”。原confirmation和recordedEvidenceLevel同时保留；REJECTED不升级/清空原来源，公开显示有限范围结论。新修正verification=null，原事件结论仍在但currentContent=false。

## 本次验证

本片实现及隔离MySQL验证已完成，待PR审核。B-05.1/B-05.2均已合入；C管理页面及D来源展示尚未联调，不宣称整个模块端到端完成。通用举报/争议裁决不在本片。

- 本次最终`node scripts/run.mjs test`：用户端单测49通过；后端公共72通过，API148项中126通过、22项MySQL专用明确跳过，实际后端198通过，无失败。此为本片最终回归，不复用上方旧基线作为本片证据。
- `CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs`：全新MySQL8.4.11，V1–V14迁移；后端220项全部通过（公共72＋API148），无跳过；共用交换套件67项，本片新增6项。覆盖两管理员争抢、同键并发重试、修正/决定双向提交顺序、普通用户及利益冲突拒绝、旧快照拒绝、公开字段隔离及原来源保留。准备状态/条件更新/审计追加三个阶段注入真实SQL约束失败，逐字段验证所有业务行完整回滚。
- 首轮MySQL约束断言因驱动将CHECK失败分类为UncategorizedSQLException而失败；数据库实际正确拒绝version2。测试改为核对实际ck_verification_state约束名及数据未变，完整重跑220项成功，没有放宽业务约束。
- `CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs --item-review-upgrade`：另一个全新MySQL，1项通过；明确V13→V14前后原履历逐字段相等，两张核验表为空，不补造核验。既有升级回归保留。
- 所有临时容器及虚构2×2图片均已清理，未连接成员日常库或输出凭据。保留既有Flyway对MySQL8.4支持范围提示，不升级依赖。未执行浏览器/微信真机/C审核页面及D来源展示联调。
