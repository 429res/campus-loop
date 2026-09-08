# C-04 第三切片：业务统计口径与管理看板

更新日期：2026-09-08。本切片修正管理概览的统计语义并接入一个已有可靠待审量；未新增后端统计接口，不代表完成交换、状态分布或趋势统计已经上线，也不代表整个 C-04 完成。

## 已接通生产指标

| 展示 | 来源 | 单位与范围 | 0 / 不可用 / 失败 |
| --- | --- | --- | --- |
| 账号记录 | `GET /api/admin/stats.users` | `cl_user` 的 user id；含全部角色与启停状态；当前全量 | 空表为0；请求失败显示“读取失败” |
| 物品记录 | `GET /api/admin/stats.items` | `cl_item` 的 item id；含全部状态与历史/下架；当前全量 | 空表为0；请求失败显示“读取失败” |
| AVAILABLE 物品 | `GET /api/admin/stats.availableItems` | 当前 `status=AVAILABLE` 的 item id；不等于精确匹配候选 | 无记录为0；请求失败显示“读取失败” |
| 待审核物品 | `GET /api/admin/items?status=PENDING_REVIEW&page=1&size=1` 的 `total` | 当前 `PENDING_REVIEW` item id；服务端完整过滤总量 | 无记录为0；独立请求失败只影响本卡片 |
| 即时推荐方案（legacy-v1） | `GET /api/admin/stats.recommendations` | 当前 2/3 人候选环，按规范化物品 ID 环去重；非 independent-v2 | 无方案为0；规模超限为 null/LIMIT_EXCEEDED；请求失败另示 |

`GET /api/admin/stats` 没有筛选参数、日期范围、时区或 `asOf`；服务端顺序读取各项，不能称为同一时点强一致快照。待审量还是另一个请求。界面因此使用“当前读数”并公开这项限制，不画趋势。

## 前端行为

- 统计、待审量和最近物品独立读取，某一路失败不会清空其他成功结果；真实0保持数字0。
- 刷新取消上一轮请求并用请求序号拒绝迟到响应，避免快速刷新时旧数据覆盖新数据。
- 待审核卡片进入 `/items?status=PENDING_REVIEW`，物品页仅接受既有合法状态 query，并仍由服务端筛选分页。
- 统计说明使用文字列出单位、范围和限制，不只依靠颜色；没有引入图表或新依赖。
- 开发路由 `/fixtures/stats` 复用生产卡片，以固定虚构数据覆盖0、推荐超限、部分失败和全部失败。夹具不调用 API、不写数据库，也不是后端验证证据。

## A/B 已确认但尚未接通

A 确认现有基础计数及权限，B 确认推荐不是交换成果。生产环境当前没有 ADMIN 交换统计/列表接口，不能调用参与者私有接口凑数。未来全量完成量应按当前 `cl_exchange.status=COMPLETED` 的唯一 exchange id 计数；期间完成量应按 UTC 的 COMPLETED 事件时间使用 `[from,to)`，而非交换创建时间。旧记录缺少完成事件时必须标明时间不可知。状态分布、创建量和状态转换量需要分别定义，不能混写。

本切片未改数据库或后端。A/B 后续若提供新字段，需同步权限、筛选、`asOf`/一致性说明、空值与部分不可用语义，以及 H2 和隔离 MySQL 的边界测试；C 再移除对应待开发说明并接真实接口。

## 验证边界

纯逻辑测试覆盖0/null/失败和快速刷新失效；管理端生产构建与浏览器覆盖记录见 [verification.md](verification.md)。既有 `MatchingLimitsIntegrationTest` 是当前基础计数、真实0和超限 null 的后端证据；本切片未重跑后端或真实 MySQL，也没有把组件夹具当作真实数据验证。
