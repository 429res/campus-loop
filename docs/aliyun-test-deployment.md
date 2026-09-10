# 阿里云同学测试环境交接

部署日期：2026-09-09；当日下午已迁移至中国香港。此环境用于同学试用，已经开放自助注册。

## 访问与账号

- 用户端：<https://amoewell.top>，进入“我的 → 登录 → 注册”。密码为 12–64 个字符，UTF-8 编码不超过 72 字节，请使用独立测试密码。
- 管理端：<https://admin.amoewell.top>，用于审核物品、管理测试用户等。管理员凭据仅保存在部署者本机的私有文件中，不进入仓库或群聊。
- 注册模式为 `DEVELOPMENT_SELF_SERVICE`，不核验校园身份。需要结束开放注册时，在服务器 `.env` 设置 `CAMPUS_REGISTRATION_MODE=CLOSED`，再执行下方启动命令使后端配置生效。
- 同学发布物品后，需要管理员审核；批准后才进入公开匹配流程。需求和物品分类应互相满足，才会出现可用推荐。

## 已部署结构与版本

- 中国香港 C 区 ECS `campus-loop-hk-20260909`，公网 IP `47.76.146.201`；Ubuntu 22.04，4 vCPU / 8 GiB / 40 GB ESSD；目录 `/opt/campus-loop`。
- Docker Compose 项目 `campus-loop-test`：MySQL 8.4.11、Java 17 Spring Boot 后端、Caddy 托管管理端与 H5。
- 当前应用提交 `defca54`，后端镜像 `campus-loop-backend:defca54`，前端镜像 `campus-loop-web:defca54`；源码与交接文档在 `feature/onboarding-exchange-community`，见 [PR #57](https://github.com/429res/campus-loop/pull/57)。香港迁移保留相同应用版本、V18 数据库及已有账号配置。
- 首次成都部署无法直接拉取 Docker Hub，镜像在本机按 `linux/amd64` 构建/拉取后导入。此次香港实例通过完整磁盘克隆保留镜像；尚未单独验证香港实例的 Docker Hub 拉取能力，后续更新应准备可导入的镜像包。
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

- 首次部署验收快照已下载到部署者本机私有目录。香港迁移前后的最新备份保存在香港实例，尚未下载到本机；此次还保留了迁移用的源快照和跨地域镜像副本。当前每日自动备份仍在同一台 ECS 上，尚无自动异地同步或自动保留清理；需定期下载并检查磁盘空间。
- 已实际将首次快照恢复到独立 Compose 项目和独立数据卷：服务健康、4 个账号、1 条已完成交换、15 个迁移记录及上传文件逐字节比对通过。恢复验证资源已清理，在线环境保持独立。
- 恢复时应先建立隔离实例/卷，导入 SQL 和 `files/`，使用记录的镜像版本验证健康及文件，再决定切换流量；不要直接覆盖在线卷。备份不包含服务器 `.env`，其安全副本需单独保管。

## 香港迁移验收（2026-09-09 下午）

- 成都源站出现阿里云备案拦截：直连 HTTP 返回 `Non-compliance ICP Filing`，公网 TLS 连接失败并经 Cloudflare 显示 525。源站内部证书校验正常；此次通过迁移香港恢复服务，没有降低 HTTPS 校验要求。
- 迁移前完成一致性备份 `/opt/campus-loop/.local/backups/20260909T060913Z`，停止应用写入并关闭数据库后克隆整台实例。香港首次启动仅运行 MySQL，重新导出全库与冻结副本逐字节比较一致，12 个上传文件全部通过 SHA-256 校验，再启动应用。
- 冻结时共 13 个账号、13 件物品、1 条交换，数据库迁移版本 V18。原账号、密码及会话配置保留；未重新初始化数据库。
- 两个域名的 A 记录均切换至 `47.76.146.201`，保留 Cloudflare 代理。香港源站直连 HTTPS 使用正常证书校验通过；公网两个域名及数据库健康检查通过。
- 现有管理员登录、账号目录、运营与动态管理读取通过；现有普通用户登录、资料、匹配、交换和动态读取通过。线上前端入口文件与发布版本逐字节一致。Chrome 实际查看交换灵感及后台普通用户/管理员目录正常，已有登录状态保留。
- 香港迁移后独立备份 `/opt/campus-loop/.local/backups/20260909T062030Z` 完成，定时备份恢复运行。成都旧实例及其系统盘已经释放；释放后再次通过两端公网健康、登录、业务读取与前端版本检查。
- 新实例按量付费，控制台创建报价为资源费用 **¥1.12/小时**（约 ¥26.88/天、¥806.40/30 天）；公网峰值 5 Mbps，按实际流量另计。保留的迁移快照可能继续产生存储费，以实际账单为准。未启用付费自动审核或其他新增付费业务服务。
- 本次只迁移既有应用与数据；没有重新执行完整功能测试、压力测试或微信真机测试。下方首次部署记录与 [后续功能验收](usability-community.md) 是各自发布阶段的证据。

## 首次部署验证与边界（2026-09-09 上午）

- 两个公网域名 HTTPS 与数据库健康检查成功；源站证书也通过直接连接校验，HTTP 跳转 HTTPS。
- 真实云端 API 验证：自助注册、独立用户登录、管理员登录、匿名/学生越权拦截、图片上传与读取、物品发布与审核、独立需求、双方匹配、幂等创建、双方确认、交接申报、归属转移、履历、退出后会话撤销。
- 实际浏览器验证：用户注册、登录、资料保存与刷新后读取、退出；管理端未登录访问业务页会跳转登录。管理员审核流程通过 HTTPS API 验证，本次未进行管理员登录后的完整浏览器巡检。
- 新前端镜像构建成功，Caddy 配置验证通过。界面修订后重新通过管理端、H5、小程序构建及 13 项账号相关检查；管理端与 H5 的线上 Logo 文件逐字节一致，小程序产物的 PNG 也与共享资源一致。浏览器核对了管理端登录与侧栏、用户端登录与个人中心，以及注册页桌面、375px 手机宽度、深色模式和空表单校验。
- 基线提交的六项 CI（Windows、管理端、H5、小程序、后端、MySQL）均成功；这些 CI 属于基线，本地部署补充未另行运行完整 CI。
- 3 个自动验收账号已停用；2 件明确标注为验收数据的物品保留为 `EXCHANGED`，以及 1 条完成交换和对应履历，以保留一致的验收证据。这些记录仍可能显示在公开列表，但不会参与新的匹配；没有模拟真实线下交接的事实。
- 本次未验证微信真机、小程序发布、持续压力负载、真实校园身份、争议仲裁或自动异地灾备。当前交付是可访问的同学测试环境。

临时部署 SSH 公钥会在交接完成时撤销；后续维护继续使用所有者已有的 ECS 登录方式。


## 同学试用反馈修复上线（2026-09-09 15:18 CST）

应用提交 `f9e33b6c095fae32685778929a9891e509d2cb23`，修复分支 `fix/usability-feedback`，PR [#58](https://github.com/429res/campus-loop/pull/58)。原八项反馈、交换存储接口报错与校园页面丰富化见[修复记录](usability-feedback.md)。

- 发布包 SHA-256：`aec952e4d8aca805da3129187cd41dcf8d20ac8ac44f40ce593f7e3cde63ef61`，通过 GitHub 预发布包分发，服务器校验后构建并切换。
- 切换前完成数据库与上传文件备份 `.local/backups/20260909T071756Z`；保留上一版本镜像、源码及配置供回滚。
- 后端和 Web 镜像均为 `f9e33b6`，MySQL 8.4.11，三个容器健康；Flyway V19 执行成功，自动备份定时器 active。
- 公网两域 HTTPS/数据库健康通过；现有管理员的账号目录、运营和动态读取通过；现有用户的资料、匹配、交换及评论预览读取通过。临时 API 登录会话已退出。
- 公网两端入口 JS 哈希与本地发布产物一致。Chrome 线上确认新首页、详情直接交换选物以及返回；真实用户的物品仅作读取和方案预览，没有替用户发出邀请。交换创建与指定评论回复的实际写入验证在隔离 MySQL 中完成。

访问：[用户端](https://amoewell.top)、[管理端](https://admin.amoewell.top)。分支已提交，主分支合并仍按成员审核要求执行。
