# A-05 备份恢复演练与部署配置

本切片只交付可重复的本地恢复演练和部署配置边界，不部署外部环境。当前上传实现是本地持久目录，不是 OSS：公开物品图位于 `UPLOAD_DIR`，履历私有证据位于它的同级 `<UPLOAD_DIR目录名>-evidence`。一个可恢复备份必须同时包含 MySQL、这两个目录及其数据库引用。

## 一键隔离演练

在仓库根目录运行：

```sh
node scripts/run.mjs backup-restore-test
```

命令要求 Docker 引擎、Node 24 和 JDK 17。脚本不读取根 `.env`，也不接受生产库参数；它会：

1. 干净构建后端，并随机选择本机端口；
2. 以随机口令启动源 MySQL 8.4.11 和源应用，库名固定为 `campus_loop_backup_source_test`；
3. 通过真实 API 创建虚构账号数据、上传、待审物品，管理员审核为可用，再创建关联该物品的需求和带私有证据的自述履历；
4. 停止源应用写入，以 `mysqldump --single-transaction` 导出数据库，并复制公开与私有上传目录；
5. 导入全新的 `campus_loop_backup_restore_test`，使用另一 JWT 密钥和另一上传目录启动恢复应用；
6. 重新登录，从应用读取物品、需求、履历、公开图片及鉴权私有证据，比较两个文件的 SHA-256，并核对 Flyway 成功/失败数和最新版本；
7. 无论成功失败，停止两个一次性容器并仅在确认路径位于操作系统临时目录且名称匹配后删除演练文件。

脚本不会输出随机口令、JWT 或登录令牌。输出的业务 ID、虚构上传 UUID 和文件摘要只用于证明当次恢复一致。备份 SQL、上传副本和容器均不进入仓库。

Windows PowerShell 与 macOS Terminal 使用同一条 Node 命令，无需 shell 重定向；Maven Wrapper 和临时路径由脚本按平台选择。当前实际演练平台是 Windows 11 + Docker Desktop；macOS 路径和进程分支已按 Node 跨平台 API 实现，但本切片未在 macOS 或 Apple Silicon Docker 上实测，D/使用该平台的成员需单独执行并记录结果。

## 生产备份与恢复顺序

部署目标未确定前，不把本地脚本改造成可指向任意数据库的生产工具。实际部署应由目标平台的受控任务或托管快照完成，并遵守以下不变顺序：

1. 记录应用版本、数据库目标和两个上传绝对路径，进入维护窗口并停止所有会产生数据库或上传写入的后端实例；仅暂停前端不能形成一致快照。
2. 备份 MySQL 全库（含 `flyway_schema_history`、触发器和约束）以及 `UPLOAD_DIR`、同级 evidence 目录。限制备份文件权限并在平台支持时加密；口令使用秘密存储注入，不写命令历史或文档。
3. 在新的空数据库和新的空上传目录恢复，绝不先覆盖当前生产库或目录。恢复账号只获得目标库所需权限。
4. 以待发布版本启动一个不接流量的后端。Flyway 只允许前向迁移，`clean` 保持禁用；健康检查 `/api/health` 成功后，核对迁移历史不存在失败记录。
5. 使用虚构或专用验收账号经 API 回读物品、需求和履历，逐一请求公开图和有权限的私有证据，并抽样比较摘要。通过后才切换流量；失败则保留原环境并调查，不在原库上反复导入。
6. 按已批准的保留期销毁临时恢复环境和过期备份。目标平台、加密、保留期、异地副本和恢复时间目标尚未确定，本切片不替团队作这些生产决策。

数据库导出成功不等于恢复成功。最低证据必须包含：新库导入、Flyway 状态、应用登录与业务回读、数据库 URL 引用和磁盘文件同时存在、公开/私有访问控制仍生效。

## 部署环境模板

后端秘密只通过主机、容器编排器或秘密管理服务注入。不要提交部署 `.env`，也不要把下列秘密改成 `VITE_*`：

| 变量 | 部署取值与边界 |
| --- | --- |
| `DB_HOST` / `DB_PORT` | 后端可达的私网 MySQL 地址；不要暴露给浏览器 |
| `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | Campus Loop 专用库和最小权限账号；口令由秘密存储注入 |
| `JWT_SECRET` | 至少 32 字节的随机秘密；轮换会使旧 JWT 验证失败，应安排重新登录 |
| `SERVER_PORT` | 后端内部监听端口，例如 `8088`；公网由 HTTPS 反向代理承接 |
| `UPLOAD_DIR` | 持久卷上的绝对路径；多实例必须挂载同一受控存储，否则不能保证文件可见性 |
| `CAMPUS_CORS_ORIGINS` | 仅列准确的 HTTPS 前端源，逗号分隔；同源代理仍建议只保留实际源，不使用 `*` |
| `CAMPUS_BOOTSTRAP_ENABLED` | 部署常驻进程固定 `false`；不得用初始化账号机制管理生产口令 |
| `CAMPUS_REGISTRATION_MODE` | 未确认校园准入前保持 `CLOSED` |
| `CAMPUS_DEMO_ENABLED` | 生产固定 `false` |
| `CAMPUS_EXCHANGE_EXPIRY_*` | 按 [A-04 运维说明](a04-exchange-expiry.md)设置；多个实例共享数据库锁语义 |

H5 推荐与 API 同源：管理端和用户端的 `VITE_API_BASE_URL` 留空，使浏览器访问 `/api` 与 `/uploads`。构建期 `VITE_*` 都是公开值。若必须跨域，两个前端均设置公开 HTTPS API origin，后端 `CAMPUS_CORS_ORIGINS` 精确允许页面 origin。微信端单独设置 `VITE_MINI_API_BASE_URL` 为真机可达的 HTTPS API，并在微信平台配置合法域名；`VITE_WECHAT_APP_ID` 也是公开标识，不是登录密钥。

以下 Nginx 片段说明路径边界，域名、证书、静态根目录和上游地址需由实际部署目标替换并复核：

```nginx
client_max_body_size 6m;

location /api/ {
    proxy_pass http://127.0.0.1:8088;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}

location /uploads/ {
    proxy_pass http://127.0.0.1:8088;
}

location / {
    try_files $uri $uri/ /index.html;
}
```

不要给 evidence 目录增加静态映射；`/api/history-evidence/{uploadId}` 必须继续经过 JWT、数据库会话和事件可见性检查。代理层和后端层的上传大小应同时保留限制。TLS、主机防火墙、数据库备份服务和对象存储均取决于尚未提供的部署目标，本切片未配置或验证。

## 当次实际证据

2026-09-08 在 Windows 工作区运行 `node scripts/run.mjs backup-restore-test`。源应用停止写入后完成数据库和两类上传复制；新隔离库启动时 Flyway 显示 14 条成功迁移、最新 V14、失败 0。恢复应用重新登录后读取虚构物品 #1、需求 #1、关联履历，并验证公开图片及鉴权私有证据的 SHA-256 与源目录一致。两个 MySQL 容器、两个应用、SQL 和上传临时目录随后自动清理。

首次演练还暴露出旧分支 `target` 残留可能让 Flyway 误报重复版本，因此脚本固定执行 Maven `clean package`；这项失败及修正后的完整重跑均未接触 `3308/campus_loop_dev`。未执行 macOS、云数据库、远程主机、外部反向代理、真实域名、微信工具/真机或灾备性能验证。
