# Linux 服务器部署（Docker Compose）

按顺序操作即可启动数据库、后端、管理端和用户端 H5。下面命令均在仓库根目录执行。首次拉取镜像与下载依赖可能需要数分钟；不必安装服务器端 Node、Java 或 MySQL。

## 1. 准备服务器

- 一台能访问 Docker Hub、npm 和 Maven 仓库的 Linux 服务器。建议预留 4 GB 内存和 10 GB 可用磁盘供构建与数据使用，实际容量随图片增长。
- 安装 [Docker Engine 与 Compose 插件](https://docs.docker.com/engine/install/)，并安装 Git。
- 如需公网 HTTPS，准备两个域名，例如 `admin.example.com` 和 `loop.example.com`，解析到该服务器。开放 TCP 80、443；数据库和 Java 端口不需要对外开放。
- 私有仓库需先取得读取权限，再通过自己的 Git 凭据克隆。

```sh
docker version
docker compose version
git clone https://github.com/429res/campus-loop.git
cd campus-loop
```

Docker 需要当前用户有执行权限。安装后若提示权限不足，按 Docker 官方文档配置用户组并重新登录。

## 2. 创建本机配置与初始账号

```sh
docker run --rm -it --user "$(id -u):$(id -g)" \
  -v "$PWD:/workspace" -w /workspace \
  node:24.20.0-bookworm-slim node scripts/setup.mjs
```

按提示设置管理员、学生用户名和密码，密码不回显。数据库密码与 JWT 密钥自动随机生成，写入根目录 `.env`。已有 `.env` 时不会覆盖。不要将该文件提交 Git、分享或粘贴到聊天；也不要使用 `docker compose config` 输出完整配置，检查语法用 `config --quiet`。

生产部署默认不导入虚构演示用户或物品，创建两个本机指定的初始账号。物品需要发布并经管理员审核后才会参与匹配。

**有域名：**用服务器本地编辑器打开 `.env`，追加以下公开配置，将示例域名替换为自己的域名：

```dotenv
ADMIN_SITE=admin.example.com
USER_SITE=loop.example.com
HTTP_BIND=0.0.0.0
```

入口容器使用 Caddy 自动申请/续期 HTTPS 证书并将 HTTP 跳转到 HTTPS。两域名必须不同，DNS、80/443 和出站网络需可用。实现依据：[Caddy 的 SPA 与 API 代理配置](https://caddyserver.com/docs/caddyfile/patterns)。

**暂时没有域名：**不用追加配置，默认仅监听服务器回环地址的 8080（管理端）和 8081（用户端）。后续可以通过 SSH 隧道体验，不会直接将无 TLS 的登录入口开放到公网。

## 3. 构建、初始化、启动

```sh
docker compose -f compose.prod.yaml config --quiet
docker compose -f compose.prod.yaml build
docker compose -f compose.prod.yaml up -d --wait mysql
docker compose -f compose.prod.yaml run --rm init
docker compose -f compose.prod.yaml up -d --wait
docker compose -f compose.prod.yaml ps
```

`init` 只运行一次性建表/账号初始化，然后正常退出。后续重复运行不会重置已存在账号的密码，也不会清库。数据库结构由 Flyway 自动迁移，无需手动导入 SQL。`up --wait` 等待数据库和后端健康检查；后端检查会实际查询数据库。

本部署和本机开发 Compose 使用不同项目名、网络和数据卷。不要混用 `compose.yaml` 与 `compose.prod.yaml`。固定使用同一目录和项目名维护同一个环境。

## 4. 打开页面并检查

有域名时，访问自己的 `https://admin.example.com` 和 `https://loop.example.com`。管理端使用管理员账号，用户端使用学生账号。

无域名时，在自己的电脑运行下面命令，并将 `user@server` 替换为实际 SSH 登录地址：

```sh
ssh -L 8080:127.0.0.1:8080 -L 8081:127.0.0.1:8081 user@server
```

保持该终端打开，在本机浏览器访问 [管理端](http://localhost:8080) 和 [用户端](http://localhost:8081)。若服务器本身有桌面浏览器，也可直接打开这两个地址。

完成以下检查：

1. 登录用户端，发布一件测试物品，确认在「我的物品」显示待审核。
2. 登录管理端，在物品审核中读取同一件物品，并填写理由通过审核。
3. 回到用户端刷新，确认物品可见；编辑会重新送审，下架后不再参与推荐。
4. 两名或三名参与者分别建立有向匹配需求后，从交换灵感发起邀请，各自确认并实际交接。不要为测试点击真实用户的交接声明。
5. 在管理端「交换记录」核对结果；在物品详情中查询履历。

健康检查地址为用户端或管理端域名下的 `/api/health`，正常返回 `code:200` 与 `database:UP`。刷新管理端深层路径（如 `/items`）应仍能正常进入页面。

## 5. 日常管理

```sh
# 查看状态与最近日志（日志在本机检查，分享前排除隐私）
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs --tail=100 backend

# 停止 / 重新启动，保留数据
docker compose -f compose.prod.yaml stop
docker compose -f compose.prod.yaml up -d --wait

# 修改 .env 后重建运行容器，使配置生效
docker compose -f compose.prod.yaml up -d --force-recreate --wait
```

需要开发自助注册时，设置 `CAMPUS_REGISTRATION_MODE=DEVELOPMENT_SELF_SERVICE` 后重建容器。该模式不核验校园身份，面向已获准的开发/演示环境；默认 `CLOSED` 时使用初始化账号。

单实例限流已经启用。后端仅信任本部署入口容器的固定 IP，入口清除客户端提供的 Forwarded 等替代转发头，并设置可信的 X-Forwarded-For/Host/Proto；后端在内部网络启用转发头还原，确保 HTTPS 同源登录不会被误判为跨域。配置依据见 [Spring Boot 代理部署说明](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)。若默认 `172.30.88.0/24` 与服务器已有网络冲突，在首次启动前同时设置 `CAMPUS_SUBNET` 与其中一个空闲的 `CAMPUS_PROXY_IP`。不要把后端或数据库增加公网端口。多实例部署需要另行实现共享限流，不属于本方案。

## 6. 备份与升级

数据库、公开图片、私有证据分别保存在 Compose 命名卷中；私有证据和公开图片共用 `/data` 卷下的不同目录。备份必须同时包含数据库和整个文件卷。证书使用独立 Caddy 卷持久化。

```sh
# 短暂停止 Web/后端写入，备份后自动恢复原先运行的服务
sh deploy/backup.sh
```

脚本输出本机备份目录；只有完整成功才生成 `COMPLETE`。将整个目录和 `.env` 分别加密保存到服务器之外。`.env` 含密钥；普通业务备份不自动复制它。备份不替代恢复演练，见 [已有隔离恢复演练](a05-backup-restore-deployment.md)。

升级前记录当前 Git 提交、镜像版本并备份，在维护窗口执行：

```sh
git rev-parse HEAD
sh deploy/backup.sh
git pull --ff-only
docker compose -f compose.prod.yaml build
docker compose -f compose.prod.yaml up -d --wait
```

检查健康与页面后再恢复使用。不要仅回滚旧镜像来撤销已经执行的数据库迁移；迁移兼容性不明时，先在独立环境恢复升级前的数据库和文件备份，并使用对应版本源码验证。

### 将备份恢复到独立环境

在**另一台服务器或另一份独立项目目录**中使用备份时的源码和配置，选择新项目名（例如通过 `.env` 设置 `COMPOSE_PROJECT_NAME=campus-loop-restore`），按需要调整域名、网络与端口，避免与原环境冲突。只对刚创建的空卷操作：

```sh
docker compose -f compose.prod.yaml build
docker compose -f compose.prod.yaml up -d --wait mysql
# 把 /secure/backup 替换为实际备份目录，先确认其中存在 COMPLETE。
docker compose -f compose.prod.yaml exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < /secure/backup/database.sql
# 创建停止状态的后端容器，再复制备份文件，不运行 init。
docker compose -f compose.prod.yaml create backend
docker compose -f compose.prod.yaml cp /secure/backup/files/. backend:/data/
docker compose -f compose.prod.yaml run --rm --no-deps --user root --entrypoint sh backend \
  -c 'chown -R 10001:10001 /data'
docker compose -f compose.prod.yaml up -d --wait
```

检查账号、物品、交换和证据回读。恢复会覆盖目标环境内容，绝不在原生产环境直接执行上述导入。原生产卷始终保留；日常维护不要使用 `down -v`。

## 7. 常见问题

| 现象 | 处理 |
| --- | --- |
| 拉取镜像、npm 或 Maven 下载失败 | 检查服务器出站网络、代理与 DNS，修复后重跑 build；不要删除数据库 |
| MySQL unhealthy / Access denied | 检查本地 `.env` 与首次建库时的密码是否一致；修改文件不会修改已有数据库用户密码 |
| 后端退出、Flyway 校验失败 | 查看 backend 日志；使用匹配的代码和迁移，不删表、不改已执行迁移 |
| 地址访问失败 | 检查 `ps`、DNS、80/443、防火墙及 `HTTP_BIND`；无域名模式必须使用 SSH 隧道 |
| 证书无法签发 | 确认两个域名指向服务器、80/443 未被其他服务占用且对公网可达 |
| 登录失败 | 管理端只能使用 ADMIN；初始化不会重置已有密码，勿重新生成 `.env` |
| 发布后首页没有物品 | 新物品先待审核，管理员通过后才公开和参与推荐 |
| 推荐为空 | 需求应为 ACTIVE，关联本人可交换物品，提供分类满足另一人的需求，形成双方或三方循环 |
| 429 请求过多 | 按接口等待时间稍后再试；避免反复提交。代理身份与部署边界见限流说明 |
| 举报成立但物品/交换没变 | 通用举报只记录处理结果；当前没有自动下架或交换裁决后果 |

微信小程序仍需自己的 AppID、合法 HTTPS API 域名、开发者权限以及开发者工具/真机验证，不能由 H5 或镜像构建成功替代，见 [微信平台说明](d04-wechat-platform-verification.md)。
