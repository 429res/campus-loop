# C-04 交换争议领域读取切片

2026-09-08。用户确认本片先完成读取与独立验证。A 通用受理后端、争议证据授权与裁决策略尚不可用；本片不新增 B 编号、不代表 C-04 或 B 全部任务完成。

## 调用契约（本片实现）

前缀 `/api/admin/exchange-disputes`，仅有效 ADMIN 会话：

| 方法/路径 | 返回 |
| --- | --- |
| GET `?page=1&size=12` | `ResultVo<PageResult<ExchangeView>>`，仅 DISPUTED，disputedAt/id 降序 |
| GET `/{id}` | `ResultVo<ExchangeView>`，仅争议交换 |
| GET `/{id}/events?page=1&size=12` | `ResultVo<PageResult<ExchangeEventView>>`，newVersion/id 升序 |

分页 page≥1、size 1–100，默认1/12；未知或重复参数400，不接受 owner、status、version 筛选。无会话/失效身份401，普通用户（含发起人）403，不存在或非 DISPUTED 404。数据不完整沿用流向投影409。请求使用已有 Authorization 头；不得把令牌写入样例或日志。响应 private, no-store，Vary: Authorization。

详情复用既有参与者与流向校验，保留 userId、公开显示名、offeredItemId、receivedItemId、邀请及交接时间、争议发起人/理由/时间与 version；参与者数量真实为2或3。allowedActions 恒为空。handedOffNote/receivedNote 为 null，不扩大私人说明读取权限。不返回创建快照、需求内容、B-05 证据、上传路径、管理员裁决或推测的实物状态。

事件字段：id、exchangeId、actorId（系统事件可为null）、eventType、previousStatus/newStatus、previousVersion/newVersion、reason、occurredAt（数据库原始时间转UTC ISO8601）。仅 DISPUTED 事件投影 reason；其他 reason 为 null，防止交接说明泄露。只读取已有 cl_exchange_event，不补造创建事件或管理员核验事实；事件时间/版本缺口不得由页面填造。

## 权限与状态矩阵

| 操作 | 身份和条件 | 本片行为 |
| --- | --- | --- |
| 登记争议 | 真实参与者，READY且已开始交接，version有效 | 继续原 B-04 POST /exchanges/{id}/dispute，进入DISPUTED，保留所有权、需求、占用 |
| 管理读取 | ACTIVE ADMIN，目标DISPUTED | 本片3个GET；允许参与交换的管理员只读，不产生裁决权限 |
| 本人读取 | 真实参与者 | 继续原本人详情；ADMIN角色本身不扩大该路径可见范围 |
| 附件受理、管理员裁决或恢复 | 尚无团队策略和A基础 | 未注册写路径，不返回假成功 |

读事务 REPEATABLE_READ，无行锁、状态迁移或写入；每次请求的records/total来自一致快照。不同分页/详情请求不承诺同一快照，C刷新应同时重读队列、详情与事件。写入继续唯一 ExchangeLifecycleService → ExchangeTransactionExecutor；不新增占用表、迁移或第二套所有权路径。

## 给 C/D 的调用与恢复样例

以下ID均为结构示意，运行测试通过真实创建和参与者确认产生虚构账号的交换，不向正常流程插入完成夹具。

- 双方：GET `/{id}` 返回2个participants、2条flows，比如物品11从101到102，物品12从102到101；不得根据物品当前owner重建历史。
- 三方：3个participants、3条flows，比如11从101到102，12从102到103，13从103到101；全部参与者可追溯，不能隐藏第三人。
- 队列空：`{"records":[],"total":0,"page":1,"size":12}`（位于统一返回的data内）。
- 事件示意：`{"id":9,"exchangeId":7,"actorId":102,"eventType":"DISPUTED","previousStatus":"READY","newStatus":"DISPUTED","previousVersion":4,"newVersion":5,"reason":"虚构物品异常","occurredAt":"2026-09-08T01:00:00Z"}`。
- 401重新登录；403显示无管理权限；404移除过期选中项并重读队列；409停止展示不完整详情并刷新/联系维护人员。未来裁决409必须人工重新判断，不能自动替换版本重放。
- D继续本人争议登记和详情，勿调用ADMIN路径。现阶段DISPUTED仅表示停止推进，不表示已退款、退回实物、重新上架或裁决完成。

## A/C 待接入边界

A须先提供真实受理目标/申请者/关联物品校验与私有证据归属契约；B再在共同交换锁序内验证和绑定，不直接复用B-05证据授权。团队仍须确定所有2/3参与者的必需材料、裁决范围与利益冲突、部分交付后的确认条件、每种状态/所有权/占用后果、决定幂等及追加修正规则。没有这些规则，不能实现管理员决定、并发裁决或其失败回滚验收。通知未授权，不发送。

## 验证

本片新增管理读取权限、分页、双方/三方流向及只读指纹测试，复用已有真实事务的登记/交接/取消/到期竞态和回滚测试。2026-09-08实际执行：`node scripts/run.mjs test` 成功，用户端53项通过；后端222项中200通过、22项MySQL专用测试在本地配置跳过。`CAMPUS_TEST_PORT=3338 node scripts/mysql-test.mjs` 成功：临时隔离MySQL 8.4.11，Flyway V1–V14，全后端222项通过、0跳过（交换领域69项，含本片新增2项读取测试）。脚本结束已清理容器，测试仅用临时虚构账号和物品。查询前后业务表指纹一致；既有交接/争议/取消/到期锁竞争及失败回滚回归通过，不将履历管理员核验并发测试误称交换裁决测试。`node scripts/repository-check.mjs`、`git diff --check`及改动文档本地链接检查通过。无页面改动或页面联调；C的生产页面仍待调用本API。
