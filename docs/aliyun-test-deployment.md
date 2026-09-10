# 阿里云同学测试环境交接

部署日期：2026-09-09。此环境用于同学试用，已经开放自助注册。

## 访问与账号

- 用户端：<https://amoewell.top>，进入“我的 → 登录 → 注册”。密码为 12–64 个字符，UTF-8 编码不超过 72 字节，请使用独立测试密码。
- 管理端：<https://admin.amoewell.top>，用于审核物品、管理测试用户等。管理员凭据仅保存在部署者本机的私有文件中，不进入仓库或群聊。
- 注册模式为 `DEVELOPMENT_SELF_SERVICE`，不核验校园身份。需要结束开放注册时，在服务器 `.env` 设置 `CAMPUS_REGISTRATION_MODE=CLOSED`，再执行下方启动命令使后端配置生效。
- 同学发布物品后，需要管理员审核；批准后才进入公开匹配流程。需求和物品分类应互相满足，才会出现可用推荐。

## 已部署结构与版本

- 成都 ECS，Ubuntu 22.04，4 vCPU / 8 GiB / 40 GB；目录 `/opt/campus-loop`。
- Docker Compose 项目 `campus-loop-test`：MySQL 8.4.11、Java 17 Spring Boot 后端、Caddy 托管管理端与 H5。
- 基线为已合并的 `main` 提交 `4e09367`；部署补充保存在 `fix/aliyun-classmate-test`。后端镜像 `campus-loop-backend:4e09367`，前端镜像 `campus-loop-web:4e09367-test4`（统一两端品牌资源，精简账号相关页面文案）。服务器 `.local/deploy/release.txt` 记录部署配置提交。
- 此实例无法直接拉取 Docker Hub；镜像在本机按 `linux/amd64` 构建/拉取后，通过 `docker save`、SSH 传输、`docker load` 导入。后续更新需要预先导入相应镜像，不能假定服务器可以在线构建。
- 使用 `compose.prod.yaml` 和 `compose.aliyun.yaml` 两份配置；后者设置内存限额、日志轮转及 Cloudflare 专用 Caddy 配置。
- 两个域名均通过 Cloudflare 代理；当前区域 SSL 模式为 Full，源站也已获得有效的 Let's Encrypt 证书，Caddy 管理续期。未修改其他域名的区域级 TLS 设置。
- 公网网站端口为 TCP 80/443；MySQL 和后端不映射公网端口。既有 SSH 等安全组规则保留。
- Caddy 只信任列出的 Cloudflare 网段，并向后端传递解析后的访客 IP；配置附带 `noindex, nofollow`。这是搜索引擎提示，不是访问控制。

## 日常操作

通过已有 ECS 登录方式进入服务器后执行：

```sh
cd /opt/campus-loop
docker compose -f compose.prod.yaml -f compose.aliyun.yaml ps
docker compose -f compose.prod.yaml -f compose.aliyun.yaml up -d --wait mysql backend web
docker compose -f compose.prod.yaml -f compose.aliyun.yaml logs --tail=100 backend web
curl -fsS https://amoewell.top/api/health
```

`.env` 含私密配置，不要提交、截图或直接输出到聊天。不要执行 `down -v`、删除数据卷或重新初始化数据库来更新版本。更新前先备份，再导入新镜像、更新标签与发布记录，最后启动并检查健康状态。

## 备份与恢复

- 已启用 `campus-loop-backup.timer`，每天北京时间 03:30 运行；会短暂停止网站和后端以获得数据库与上传文件的一致快照，完成后恢复服务。
- 备份在 `/opt/campus-loop/.local/backups/<UTC时间>/`，包括 `database.sql`、`files/`、镜像与版本记录；只有包含 `COMPLETE` 的目录才是成功快照。
- 手动备份和检查定时器：

```sh
sudo systemctl start campus-loop-backup.service
sudo systemctl status campus-loop-backup.service --no-pager
sudo systemctl list-timers campus-loop-backup.timer --no-pager
```

- 部署验收快照已下载到部署者本机私有目录。当前每日自动备份仍在同一台 ECS 上，尚无自动异地同步或自动保留清理；需定期下载并检查磁盘空间。
- 已实际将首次快照恢复到独立 Compose 项目和独立数据卷：服务健康、4 个账号、1 条已完成交换、15 个迁移记录及上传文件逐字节比对通过。恢复验证资源已清理，在线环境保持独立。
- 恢复时应先建立隔离实例/卷，导入 SQL 和 `files/`，使用记录的镜像版本验证健康及文件，再决定切换流量；不要直接覆盖在线卷。备份不包含服务器 `.env`，其安全副本需单独保管。

## 本次验证与边界

- 两个公网域名 HTTPS 与数据库健康检查成功；源站证书也通过直接连接校验，HTTP 跳转 HTTPS。
- 真实云端 API 验证：自助注册、独立用户登录、管理员登录、匿名/学生越权拦截、图片上传与读取、物品发布与审核、独立需求、双方匹配、幂等创建、双方确认、交接申报、归属转移、履历、退出后会话撤销。
- 实际浏览器验证：用户注册、登录、资料保存与刷新后读取、退出；管理端未登录访问业务页会跳转登录。管理员审核流程通过 HTTPS API 验证，本次未进行管理员登录后的完整浏览器巡检。
- 新前端镜像构建成功，Caddy 配置验证通过。界面修订后重新通过管理端、H5、小程序构建及 13 项账号相关检查；管理端与 H5 的线上 Logo 文件逐字节一致，小程序产物的 PNG 也与共享资源一致。浏览器核对了管理端登录与侧栏、用户端登录与个人中心，以及注册页桌面、375px 手机宽度、深色模式和空表单校验。
- 基线提交的六项 CI（Windows、管理端、H5、小程序、后端、MySQL）均成功；这些 CI 属于基线，本地部署补充未另行运行完整 CI。
- 3 个自动验收账号已停用；2 件明确标注为验收数据的物品保留为 `EXCHANGED`，以及 1 条完成交换和对应履历，以保留一致的验收证据。这些记录仍可能显示在公开列表，但不会参与新的匹配；没有模拟真实线下交接的事实。
- 本次未验证微信真机、小程序发布、持续压力负载、真实校园身份、争议仲裁或自动异地灾备。当前交付是可访问的同学测试环境。

临时部署 SSH 公钥会在交接完成时撤销；后续维护继续使用所有者已有的 ECS 登录方式。
