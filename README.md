# Campus Loop · 校园循环

基于物品需求匹配的校园闲置交换系统。一个仓库包含 Vue 管理端、UniApp 用户端（H5 / 微信）和 Spring Boot 后端。

**第一次部署：直接阅读 [Linux + Docker Compose 部署教程](docs/deployment.md)。** 服务器只需安装 Git 与 Docker，无需另外安装 Java、Maven、Node 或 MySQL。教程包含无域名试运行、域名 HTTPS、初始化账号、升级、备份和排错。

## 能做什么

- 用户名或邮箱登录、邮箱验证注册、资料和密码管理。
- 物品发布、编辑、送审、下架、收藏；独立需求管理和双方/三方可解释匹配。
- 发起交换、确认邀请、取消、超时释放、交出/收到声明与原子流转。
- 物品自述履历、私有证据、参与者确认、管理员核验。
- 管理端账号、分类、审核、交换追溯、物品举报受理和处理。

推荐本身不占用物品；正式交换在服务端事务中重新校验。交换争议可以登记并停止流转，目前**没有管理员裁决或恢复流程**。注册默认关闭；配置 SMTP 后可开启邮箱验证注册（EMAIL_VERIFIED）。历史账号保留使用资格，新账号需验证邮箱才能互动；邮箱验证不等于校园身份认证。支持自愿开启账户安全邮件提醒和千问辅助发布、审核，配置方法见[账号与社区升级](docs/community-account-upgrade.md)。微信身份登录、短信、Passkey 和 OSS 尚未接入。

## 选择你的入口

| 需求 | 文档 |
| --- | --- |
| Linux 服务器部署，供他人访问 | [部署教程](docs/deployment.md) |
| Windows / macOS 本机运行、修改源码 | [开发环境](docs/development.md) |
| 了解此次修复与实际验证结果 | [全项目审查报告](docs/full-project-audit.md) |
| 查看接口与业务规则 | [API 契约](docs/api-contract.md)、[架构](docs/architecture.md) |
| 参与项目开发 | [协作指南](CONTRIBUTING.md)、[团队约定](AGENTS.md) |
| 了解实现范围和历史验收 | [路线图](docs/roadmap.md)、[验收记录](docs/verification.md) |

## 源码目录

```text
project-self/       Vue 3 + Element Plus 管理端
wx-project-self/    UniApp 用户端：H5 / 微信小程序
sys-project/       Spring Boot 3 + Java 17 后端
shared/            共享设计变量
deploy/            容器镜像、HTTPS 入口、备份脚本
compose.prod.yaml  完整部署（MySQL + 后端 + 两前端）
compose.yaml       本机开发专用 MySQL
scripts/           初始化、构建、测试和恢复演练
```

本机开发依赖版本见 [开发环境](docs/development.md)。前端使用各自 lockfile 和 `npm ci`，后端使用 Maven Wrapper。容器部署使用仓库内 Dockerfile 构建，不读取本机依赖或上传目录。
