# A-01 第二批：管理员查询与账号启停

状态：本功能分支已实现后端接口、契约、迁移与隔离测试，待其他成员审核及 CI 通过后合并。本批不包含注册、校园认证、管理员赋权、硬删除用户或管理页面，也不代表整个 A-01 完成。

## 给 C 的接口字段

`GET /api/admin/users?page=1&size=12&keyword=&role=&status=` 需要 ADMIN。`keyword` 同时匹配用户名与显示名称；`role` 可选 `ADMIN/USER`，`status` 可选 `ACTIVE/DISABLED`。结果统一为：

```json
{
  "records": [{
    "id": 42,
    "username": "sample_user",
    "displayName": "示例同学",
    "role": "USER",
    "status": "ACTIVE",
    "version": 0,
    "createdAt": "2026-09-08T00:00:00"
  }],
  "total": 1,
  "page": 1,
  "size": 12
}
```

`PATCH /api/admin/users/{id}/status` 只接受：

```json
{
  "status": "DISABLED",
  "version": 0,
  "reason": "违反平台使用规范"
}
```

成功返回更新后的同一账号安全字段。C 应使用列表最新 `version`；409 时保留确认上下文，重新查询记录后再让管理员判断，不自动覆盖。状态相同且版本匹配是幂等成功，不增加版本或审计。理由去除首尾空白后1–500字符。额外字段（包括 `role`、`passwordHash`）返回400。

`GET /api/admin/users/{id}/status-audits?page=1&size=20` 返回 `id,targetUserId,operatorUserId,operatorUsername,operatorDisplayName,previousStatus,newStatus,reason,previousVersion,newVersion,createdAt` 分页。接口与审计表都不包含口令、密码哈希、令牌、会话标识或认证头。

## 错误与重新登录

- 401：未登录、会话已撤销，或操作者在并发等待期间已被停用；沿用统一退出处理。
- 403：已登录但不是 ADMIN。
- 400：分页、筛选、状态、版本、理由或 JSON 字段不符合契约。
- 404：目标账号不存在。
- 409：版本过期、当前管理员尝试停用自己，或操作会停用最后一个可用管理员。

停用成功会在同一事务撤销目标账号全部会话并阻止新登录。重新启用不会复活旧令牌，目标用户必须重新登录。停用不删除或隐藏既有物品；公开读取仍服从物品状态，但匹配候选会排除停用账号。

失败响应示例：

```json
{ "code": 409, "msg": "账号状态已变化，请刷新后重试", "data": null }
```

## 并发与审计

登录和启停锁定同一用户行：并发登录若先提交，新会话由停用事务删除；停用若先提交，登录失败。启停还按 id 锁定管理员集合并重新确认操作者状态，避免两个管理员交叉停用后失去管理入口。状态条件更新、停用时删除会话及新增审计原子提交；失败不留下部分审计或会话变化。
