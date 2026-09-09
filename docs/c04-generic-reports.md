# C-04/D-04 通用举报后端接入说明

更新日期：2026-09-09。本切片由 A 提供真实举报持久化、本人读取、ADMIN 队列/受理/处理、私有证据授权和追加审计；C 的管理页面与 D 的用户提交/结果页面仍需分别接线。交换争议裁决后果未接通，因此不能据此标记整个 C-04 或 D-04 完成。

## 已确认范围

- 第一版目标只有 `ITEM`。提交时目标必须处于现有公开可见状态 `AVAILABLE/RESERVED/EXCHANGED`；不存在或不可见统一返回 404。提交后保存安全标题快照，目标后来下架时仍可回读快照，`targetAvailable=false`。
- 状态为 `SUBMITTED → IN_REVIEW → RESOLVED`，当前成功版本依次为 0、1、2。最终决定只有 `UPHELD` 和 `DISMISSED`，没有重开、硬删除、管理员代替用户提交或批量决定接口。
- 受理人不独占最终处理权。任一仍有效的 ADMIN 可在最新 `IN_REVIEW/version` 上决定；`acceptedBy` 与 `decidedBy` 分别记录，避免受理人停用后队列永久卡住。
- 同一举报人在同一 ITEM 上同时只能有一条 `SUBMITTED/IN_REVIEW` 举报；旧举报终局后可基于新事实再次提交。
- 普通举报决定只写举报状态与审计，不下架物品，不修改 owner、交换、需求、占用或履历。`EXCHANGE` 目标会被 400 拒绝；参与者登记争议继续使用 B-04 的 `POST /api/exchanges/{id}/dispute`。B 尚未提供已确认的管理员争议后果服务，因此本切片没有“裁决并释放占用”等动作，也没有借举报接口绕过交换状态机。

## D 提交与本人读取

先调用现有 `POST /api/uploads/evidence` 上传 0–5 张图片，再提交严格 JSON；五个字段全部必填，未知字段拒绝：

```json
{
  "targetType": "ITEM",
  "targetId": 83,
  "reason": "虚构示例：描述与现场情况不一致",
  "evidenceUploadIds": ["00000000-0000-4000-8000-000000000083"],
  "idempotencyKey": "report-00000000-0000-4000-8000-000000000083"
}
```

`reason` trim 后 1–1000 字；`targetId` 为正整数；证据 UUID 不得重复；`idempotencyKey` 为 1–64 位小写字母、数字或 `._:-`。举报人来自登录会话，客户端传 `reporterId/status/decision/role/ownerId` 等额外字段会整体 400。网络结果不确定时必须保留原幂等键和原内容重试：同键同内容返回同一举报的最新回读，同键不同内容 409。换新键不能绕过同目标待处理举报限制。

成功返回统一 envelope，`acceptedBy/decidedBy` 在相应动作前为 null：

```json
{
  "code": 200,
  "msg": "成功",
  "data": {
    "id": 2401,
    "targetType": "ITEM",
    "targetId": 83,
    "targetAvailable": true,
    "targetSummary": "虚构蓝牙键盘 · ITEM #83",
    "reporterDisplayName": "虚构提交同学",
    "status": "SUBMITTED",
    "version": 0,
    "reason": "虚构示例：描述与现场情况不一致",
    "evidence": [{"id": 301, "displayName": "证据-1.png", "contentType": "image/png", "size": 284103, "accessStatus": "AVAILABLE"}],
    "createdAt": "2026-09-09T01:02:03Z",
    "acceptedBy": null,
    "acceptedAt": null,
    "decision": null,
    "decisionReason": null,
    "decidedBy": null,
    "decidedAt": null
  }
}
```

| 接口 | 说明 |
| --- | --- |
| `POST /api/reports` | 登录用户提交，成功返回本人详情 |
| `GET /api/reports/mine?page=1&size=12&status=&targetType=` | 本人分页；`page≥1`、`1≤size≤100` |
| `GET /api/reports/mine/{id}` | 本人详情；他人记录统一 404 |
| `GET /api/reports/mine/{reportId}/evidence/{evidenceId}/content` | 本人读取已关联证据内容 |

本人列表字段为 `id,targetType,targetId,targetAvailable,targetSummary,reporterDisplayName,status,version,createdAt,acceptedBy,acceptedAt,decision,decisionReason,decidedBy,decidedAt`。详情另有 `reason,evidence`；处理人只投影 `{displayName}`，不返回用户 ID。UTC 时间为带 `Z` 的 ISO-8601 字符串。

## C 管理端接线

| 接口 | 请求/筛选 |
| --- | --- |
| `GET /api/admin/reports` | `page,size,keyword,status,targetType`；keyword 查举报编号或目标摘要 |
| `GET /api/admin/reports/{id}` | 授权详情，含全文理由和证据元数据 |
| `POST /api/admin/reports/{id}/accept` | 严格 `{version,reason}`，reason 1–1000 字 |
| `POST /api/admin/reports/{id}/decision` | 严格 `{version,decision,reason}` |
| `GET /api/admin/reports/{id}/audits?page=1&size=12` | 时间正序的追加审计 |
| `GET /api/admin/reports/{reportId}/evidence/{evidenceId}/content` | ADMIN 授权证据内容 |

管理员列表不含 `reason/evidence`，必须在打开详情后读取。审计字段为 `id,action,actor,previousStatus,newStatus,previousVersion,newVersion,reason,decision,createdAt`；`actor` 只有显示名，不含账号 ID、请求摘要、幂等键、口令或 token。

同一管理员对已成功动作发送完全相同的 actor/action/version/reason/decision 可安全回读且不新增审计；换管理员、改理由/决定或使用其他旧版本均 409。C 收到 409 后应重新读取详情并保留未提交理由，不能自动套用新 version 重放。

## 证据可见范围

- 只接受举报人本人、用途为 `PRIVATE_EVIDENCE` 且文件仍有效的现有上传；他人上传、公开物品图片、重复 UUID、服务器路径和外链均 400。
- `cl_report_evidence` 保存上传关联、生成显示名、PNG 类型、大小与提交时 SHA-256。响应只给不透明的举报证据数字 ID、`displayName/contentType/size/accessStatus`，不回传上传 UUID、文件路径、哈希或公开 URL。
- 只有举报人自己的详情和 ADMIN 详情可见证据元数据；只有上述两个受控内容端点可读内容。内容返回 `image/png`、`private, no-store`、`nosniff`。文件缺失或大小变化时元数据为 `MISSING`；内容读取还会核对提交时摘要，失败统一 404。
- 队列、公开物品接口、匹配与交换读取均不会附带举报理由或证据。普通用户不能通过猜测 report/evidence ID 读取他人材料。

## 事务、并发与迁移

V15 新增 `cl_report`、`cl_report_evidence`、`cl_report_audit`，不修改 V1–V14。创建在 READ COMMITTED 事务内按 `reporter user → target item` 加锁，锁后重校验身份、幂等、待处理重复、目标状态和证据归属，再一起写举报、证据关联与 SUBMIT 审计。管理员动作按 `admin user → report` 加锁，条件更新版本并在同一事务追加 ACCEPT/DECIDE 审计。数据库唯一键保护 `(reporter,idempotencyKey)`、每举报每动作和证据位置/上传关系；约束失败整笔回滚。

实际测试用虚构账号、物品、争议交换与图片覆盖：D 提交后本人/C 回读、401/403、非法/失效目标、伪造字段、他人/重复证据、列表隐私、同键重试与不同内容冲突、同目标待处理重复、目标失效快照、证据篡改、两个管理员竞争、约束故障回滚、审计唯一，以及 UPHELD 后 item/exchange/hold 逐行不变。目标用例在 H2 通过 4 项、MySQL 专用行锁用例跳过 1 项；一次性 MySQL 8.4.11 从空库应用 V1–V15 后 5/5 通过，容器已自动删除。完整仓库回归结果见 `verification.md`。

## 失败样例

```json
{"code":400,"msg":"举报请求字段或类型不正确","data":null}
```

```json
{"code":403,"msg":"需要管理员权限","data":null}
```

```json
{"code":404,"msg":"举报目标不存在或不可提交","data":null}
```

```json
{"code":409,"msg":"举报状态、版本或重复请求内容已变化，请刷新后重试","data":null}
```

未登录为 401；图片类型/归属错误为 400，超过上传总限制沿用现有 413。错误保持真实 HTTP 状态，不包成 200。
