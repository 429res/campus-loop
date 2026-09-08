# Campus Loop · Codex 团队约定

本文件是全仓库共享的开发入口，供四名成员及其 Codex 使用。目标是保持框架、业务语义和验收标准一致，允许成员在共同契约内选择实现细节。详细规范按任务读取，不把一次性任务或个人习惯写成永久约束。

## 开始任务

- 先检查当前分支、`git status` 和相关源码；保护已有改动，不重建模板或覆盖他人工作。根据任务说明确定范围和验收标准，复杂任务给简短计划后继续实施。
- 只读本次相关文档和依赖入口。代码与契约不一致时先查明实际行为；修复偏差或提出明确的契约变更，不按个人习惯另造一套。
- 非关键细节采用与现有实现一致的默认值。已授权的本地实现与必要验证自主完成；涉及尚未授权的破坏性操作、发布或权限变更时才请求对应授权。
- 在 `feature/*` 或 `fix/*` 分支工作。跨模块先在 Issue/PR 说明字段、状态、迁移及调用方影响；无需为普通实现细节增加审批步骤。

## 固定工程方向

| 位置 | 约定 |
| --- | --- |
| `project-self` | Vue 3、Vite、Element Plus、Pinia、Vue Router、Axios 管理端 |
| `wx-project-self` | UniApp、Vue 3；同时兼容 H5 和微信，平台差异用现有条件编译/适配层 |
| `sys-project` | Spring Boot 3、Java 17、Maven、MyBatis-Plus、MySQL；保留 `sys-project-com` / `sys-project-api` |
| 后端实现 | `edu.campusloop` 包；业务沿 `web/<模块>/controller、service、mapper、entity、dto、vo` 的已有方式扩展，按实际需要创建；控制器不承载事务业务 |

- 版本以 [README](README.md)、两端 `package-lock.json` 和 Maven Wrapper 为准；前端使用 `npm ci`。保留各端适配的构建器，不能为了版本统一破坏 UniApp。
- 优先扩展已有认证、请求、分页、异常、校验、上传及公共控件，不平行引入第二套。管理端请求入口 `project-self/src/http/index.js`，用户端 `wx-project-self/src/common/http.js`；后端复用 `ResultVo`、`PageResult`、`ApiException`、`GlobalExceptionHandler` 和现有认证服务。
- 架构替换、全仓语言迁移、新中间件或依赖升级应作为有理由、有兼容验证的独立变更；不能夹带在普通功能 PR 中。初始化和运行不依赖成员私人路径、技能、账号或全局 Codex 配置。

## 共同业务规则

- [API 契约](docs/api-contract.md)定义字段、分页、错误和当前可调用能力；[架构](docs/architecture.md)定义模型和状态机。新接口先同步契约，再实现消费端；HTTP 错误不包成成功，未实现操作明确待开发。
- 登录用户、角色及物品归属由服务端确定。沿用 JWT + 数据库会话与 BCrypt，不能仅靠前端路由、按钮隐藏或客户端传入 owner/role 做权限控制。
- 第一版匹配是有向规则：提供者物品满足接收者需求，环长 2 或 3，每人一件，用户与物品唯一、状态可交换；分类硬匹配、标签用于排序。不得悄悄改成价格交易、随机推荐或必需外部 AI 的功能。
- 推荐只读，不建立交换或占用。正式创建须在事务内重新校验，统一锁定顺序、唯一占用与幂等；确认、取消、超时、交接均遵循同一状态机。改规则须补去重、非法候选、空结果及相应并发边界测试。
- 履历保留来源、作者、发生与记录时间；自述、参与者确认、管理员核验不可混淆，用户不能自行提升可信度。详细实现状态见[路线图](docs/roadmap.md)，预留表或接口草案不等于功能完成。

## 共同视觉与控件

- 使用校园产品品牌、`shared/design-tokens.css` 与已有公共组件/材质层；白浅灰底、粉色主要行动、蓝色辅助，兼容深色。不得恢复参考工程角色品牌或复制其他产品的商标界面。
- 导航、表单、按钮、选择、分页、上传、提示、菜单、弹窗、抽屉都继承公共状态和动效；新控件同时登记覆盖清单。复用前先检查两端控件展示页，避免局部再造主题。
- 表格、图片、正文和长表单保持稳定可读；验证焦点、键盘、加载/禁用/失败、快速反向开关和减少动态效果。微信原生控件保留平台能力，材质是 Web/小程序近似。[设计系统](docs/design-system.md)及两端 `docs/liquid-glass-controls.md` 提供细节。

## 数据与环境

- 启动使用根目录 `scripts/run.mjs` 加载本机 `.env`，命令见 README；每人独立开发库或容器。自动化测试使用隔离配置，不能连接成员日常库或参考项目数据库。
- Flyway `sys-project/sys-project-api/src/main/resources/db/migration` 是表结构版本来源；已共享或运行的迁移不改写，新增迁移由相关成员协调序号。禁止以 DROP、清库或导入真实数据作为默认初始化/修复方法。
- 凭据、令牌、真实上传和业务数据不进入 Git、文档、截图或日志。前端只接收公开配置；开发账号通过本机初始化设置。对外依赖必须可配置并明确未启用状态。

## 验证与交付

- 执行与风险对应的检查：管理端 `npm run build`；用户端 `npm run build:h5` 与 `npm run build:mp-weixin`（均在对应子目录）；后端从根执行 `node scripts/run.mjs test`。数据库语义/并发改动增加隔离 MySQL 验证，不能只依赖 H2。
- 页面改动做实际浏览器交互；共享控件覆盖两端、响应式和降级。纯文档改动检查事实、命令及链接，不机械重跑全套业务测试。根 `node scripts/repository-check.mjs` 检查交付文件和锁文件一致性。
- PR 写清变更、契约/迁移影响、实际验证和剩余限制。构建、自动化、浏览器、真实数据库、微信工具/真机分开报告；不得把旧验收记录当成本次已运行结果。
- 至少一名其他成员审核、CI 成功后合并；不自行绕过审核直推 main。共享文件协商和当前平台保护限制见 [CONTRIBUTING](CONTRIBUTING.md)。主责不限制目录权限，跨端协作按任务需要进行。

## 文档入口与维护

[README](README.md)：安装启动；[CONTRIBUTING](CONTRIBUTING.md)：Codex 接入、分支和协作；[architecture](docs/architecture.md)：模型/事务；[api-contract](docs/api-contract.md)：接口；[design-system](docs/design-system.md)：视觉；[roadmap](docs/roadmap.md)：实现状态；[team-work-plan](docs/team-work-plan.md)：可认领模块与验收；[verification](docs/verification.md)：既有证据。

持久决策随相关代码更新文档，通过同一 PR 共享。不要另建 `agent.md`、个人版规则或无必要的 `AGENTS.override.md` 来分叉全仓约定；确需子目录专项规则时仅补该范围的差异并评审。若发现指令冲突，说明来源和影响，不自动修改个人全局配置。成员接入与规则加载核对方法见 CONTRIBUTING。
