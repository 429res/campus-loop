# Campus Loop · 校园循环

《基于需求匹配与多方置换的校园闲置物品循环管理系统》四人协作初始工程。一个仓库包含 Vue 管理端、UniApp 用户端及 Spring Boot 后端。以物品和需求为有向关系，解释双方和三方循环交换；推荐不会占用物品。

当前迭代实现登录、发布、本人列表、详情、后台记录、物品审核与匹配推荐；确认、交接、争议和履历审核属于后续迭代，见 [路线图](docs/roadmap.md)。此工程是开发基础，不是完整可上线业务系统。

全员使用 Codex 开发时，共同遵循根 [AGENTS.md](AGENTS.md)；首次接入见 [CONTRIBUTING](CONTRIBUTING.md)，可认领模块与依赖顺序见 [四人模块计划](docs/team-work-plan.md)。

## 环境与目录

| 工具 | 固定版本 | 用途 |
| --- | --- | --- |
| Node.js | 24.20.0 | 两前端及跨平台脚本；`.nvmrc` / `.node-version` |
| npm | 12.0.2 | 使用各自 `package-lock.json` 和 `npm ci` |
| JDK | Temurin 17.0.20.1 | Spring Boot 3；不要使用机器默认 JDK 26 |
| Maven | 3.9.16 | 已提交 Maven Wrapper，无需系统 Maven |
| MySQL | 8.4.11 | Compose 固定镜像；本地服务用兼容的 MySQL 8.4 |
| 管理端构建器 | Vite 8.2.2 起的原锁定版本 | 沿用参考工程 lockfile |
| UniApp 构建器 | Vite 5.2.8 | 与 UniApp 适配，不统一升级 |

```text
project-self/                  Vue 3 / Element Plus 管理端
wx-project-self/               UniApp Vue 3 / H5 / 微信小程序
sys-project/
  sys-project-com/             返回、错误、匹配等公共基础
  sys-project-api/             controller / service / mapper / entity
shared/design-tokens.css       三端共用品牌语义变量
compose.yaml                   本机独立 MySQL（127.0.0.1:3308）
scripts/                       本地初始化、环境加载和验证命令
docs/                          架构、接口、设计、迁移及验收记录
.github/                       CI、Issue 与 PR 模板
```

## 从全新 clone 启动（Windows / macOS）

每位成员在自己的电脑执行以下步骤，不共享 `.env`、数据库卷或上传目录。仓库仅供 `429res` 组织成员访问；组织成员还需有本仓库 Write 权限才能推送功能分支。

1. 安装 Git、上表版本的 Node.js、JDK 17 和 Docker Desktop（启动 Docker 引擎）。检查 `node -v`；运行 `npm install -g npm@12.0.2` 固定 npm。macOS 可用 nvm 后 `nvm install && nvm use`；Windows 可用 nvm-windows 安装并选择 `24.20.0`。
2. 配置 `JAVA_HOME` 指向 JDK 17 安装目录、将其 `bin` 加入 PATH。macOS 本项目脚本会在未设置 JAVA_HOME 时选择已安装的 JDK 17；Windows 在系统环境变量中设置自己的安装路径，然后重开终端。
3. 克隆并安装依赖：

```sh
git clone https://github.com/429res/campus-loop.git
cd campus-loop
node scripts/run.mjs install
node scripts/run.mjs doctor
node scripts/setup.mjs
```

`setup` 在交互终端询问两个本地用户名及口令，口令不回显。生成的根目录 `.env` 被 Git 忽略，数据库口令和 JWT 密钥随机产生；该文件仅保存在本机。需要修改时用本地编辑器打开，禁止提交、分享或粘贴到聊天。脚本不覆盖已有 `.env`。一次性自动隔离验收可用 `node scripts/setup.mjs --generated`，账号凭据仍只写本机 `.env`。

4. 启动新项目数据库，运行版本迁移并初始化账号与虚构演示数据：

```sh
node scripts/run.mjs db
node scripts/run.mjs init
```

`db` 等待 Compose 健康检查；`init` 构建后端，开启一次性 bootstrap，完成后退出。Flyway 自动执行版本迁移；初始化可重复运行，不重置已存在账号、不清空数据。首次 Maven 下载和 Docker 拉取需要网络。初始化失败请阅读错误、修正本地环境后重试，不删除数据库解决问题。

5. 分别在三个终端（均位于仓库根目录）运行：

```sh
node scripts/run.mjs backend
node scripts/run.mjs admin
node scripts/run.mjs h5
```

打开管理端 [localhost:5174](http://localhost:5174)、用户端 [localhost:5175](http://localhost:5175)。管理端使用本机初始化的管理员账号，用户端使用学生账号。在用户端发布物品后刷新详情，再到管理端物品页查看同一条记录。两个前端开发代理指向 [127.0.0.1:8088](http://127.0.0.1:8088)。

源码更改后重新运行 `init` 或在 `sys-project` 使用 Wrapper 构建，再启动后端；前端开发服务器支持热更新。停止进程用 Ctrl+C；数据库 `node scripts/run.mjs db-stop`。`docker compose down` 保留数据卷，禁止日常使用 `down -v`。

## 配置确实如何加载

根目录 `.env.example` 是字段说明，不是运行凭据。`scripts/env.mjs` 使用 Node 原生 `parseEnv` 读取根 `.env` 并传给子进程；现有系统环境变量优先，脚本将 `UPLOAD_DIR` 解析为仓库内绝对路径。Spring Boot `application.yml` 引用这些环境变量。直接在 IDE 启动 Java 时，要给运行配置设置同样的环境变量；Spring Boot 不会自动读取根 `.env`。

`DB_HOST/PORT/NAME/USERNAME/PASSWORD`、`JWT_SECRET`、`SERVER_PORT`、`UPLOAD_DIR` 可设置。初始化才使用 `CAMPUS_BOOTSTRAP_ENABLED` 和本地账号变量；平时的 `backend` 命令强制关闭 bootstrap。注册默认使用 `CAMPUS_REGISTRATION_MODE=CLOSED`；仅本地开发联调可显式改为 `DEVELOPMENT_SELF_SERVICE`，这不代表校园身份已核验，公开部署前必须重新确定准入。前端 `.env.example` 只含公开 API 地址、代理地址及微信 AppID；不要把任何口令放进 `VITE_*`。

如需调整 API 端口，两前端对应 `.env.local` 的 `VITE_API_PROXY` 也要同步。部署 H5 默认用同域 `/api` 和 `/uploads`，配置反向代理；跨域场景设置允许来源和 `VITE_API_BASE_URL`。上传默认落在 `.local/uploads`，OSS 尚未接入，不能填凭据就假定支持。

## 使用已有本地 MySQL

不运行 `db`。通过本地数据库客户端创建**新的** `campus_loop_dev` 数据库（utf8mb4）和仅对该库有权限的开发账号，使用本机设置的独立口令；把地址、端口和账号填入根 `.env`。不要使用参考项目数据库，不导入旧数据库备份。随后运行 `init`。Flyway 负责建表，已有表结构不兼容时会报错，不自动覆盖。

Compose 数据卷与 `.env` 口令在首次建库后应保持一致。修改 `.env` 不能修改已有 MySQL 用户口令；需要在数据库内通过本地受控步骤同步，或另建新项目专用数据卷进行隔离验证。

## 微信小程序

H5 开发不需要微信账号。小程序单独在 `wx-project-self/.env.local` 设置 `VITE_WECHAT_APP_ID`、`VITE_MINI_API_BASE_URL`，再运行：

```sh
node scripts/run.mjs wechat
```

用微信开发者工具导入 `wx-project-self/dist/build/mp-weixin`，使用自己的测试 AppID 或工具支持的测试模式。真实 AppID 由拥有者添加准确的开发者成员；GitHub 权限不等于微信权限。模拟器可访问本机 `127.0.0.1:8088`；真机需要可达的后端 HTTPS 地址与合法域名配置，不能使用真机自身的 localhost。开发工具的本地免域名校验仅用于本地调试，不能代替正式环境配置。

## 验证与继续开发

```sh
node scripts/run.mjs test     # 发布会话/按钮回归 + 隔离 H2 后端与匹配测试
node scripts/run.mjs build    # 上述前端回归 + 三端构建、微信按钮产物检查 + 后端 verify
```

推荐执行 `node scripts/mysql-test.mjs` 自动建立随机口令的临时容器、运行测试并停止容器，需 Docker 引擎，默认3319端口（`CAMPUS_TEST_PORT`可改）。

连接预先建好的隔离 MySQL 测试库时使用显式的 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD`，只接受 `localhost` 或 `127.0.0.1`、显式端口及 `campus_loop_*test` 库名（例如 `campus_loop_ci_test`），再执行 `node scripts/run.mjs mysql-test`。只对独立临时测试库运行。GitHub Actions 自建 MySQL service，绝不读取开发者 `.env`。

阅读 [协作规则](CONTRIBUTING.md)、[架构](docs/architecture.md)、[API 契约](docs/api-contract.md)、[设计系统](docs/design-system.md)、[迁移说明](docs/migration.md)、[验收记录](docs/verification.md)。每次改动按实际风险验证，并区分构建、浏览器、数据库和微信真机结果。

### 交换到期任务

后端默认每30秒扫描最多50条到期交换，复用确认/取消事务，仅处理尚未交接的等待或READY状态。暂停、批次参数、持久退避、重启恢复及B/C/D截止语义见[A-04运维说明](docs/a04-exchange-expiry.md)。启用前须部署A-03/B-03.2与V10；定时扫描不依赖客户端倒计时或内存任务列表。
