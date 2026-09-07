# 后端迁移记录

本记录基于参考工程的实际源码检查。参考目录只读；新工程没有迁入 Git 历史、目标目录、上传文件、数据库内容、日志、个人配置或 OSS 凭据。

| 参考工程入口 | 新工程处理 |
| --- | --- |
| `sys-project/pom.xml`、两个子模块 POM | 保留父工程 + `sys-project-com` + `sys-project-api`，保留 Spring Boot 3.4.3、MyBatis-Plus 3.5.5。包名由 `cn.magic` 调整为 `edu.campusloop`。 |
| `sys-project-com/.../utils/ResultVo.java` | 复用统一 `code/msg/data` 返回约定，改为类型安全的 Java 17 record；失败的 HTTP 状态与 code 一致。 |
| `sys-project-api/.../config/MyBatisPlusConfig.java` | 复用分页拦截器与 Mapper 扫描方式；新业务分页默认 12、上限 100。 |
| `sys-project-api/.../auth/PasswordService.java` | 复用 BCrypt 及 UTF-8 72 字节上限；新账号最少 12 字符，移除旧 MD5 和明文兼容分支，不迁移旧账号。 |
| `sys-project-api/.../auth/AdminAuthInterceptor.java` | 复用 Spring 实际匹配路由 + 拦截器认证方式，公共接口白名单显式列出；管理接口统一检查 ADMIN。 |
| `sys-project-api/.../config/GlobalExceptionHandler.java` | 复用全局异常与 Jakarta 参数校验处理；统一真实 HTTP 状态、不输出 SQL 绑定参数或凭据。 |
| `sys-project-api/.../web/common_controller/ImageUploadController.java` | 复用本地目录、UUID 文件名、路径归一化思路；强化为登录上传、文件内容解码、像素限制、重编码 PNG、上传归属校验。 |
| `sys-project-api/.../web/goods/service/impl/GoodsServiceImpl.java` | 保留 Controller / DTO / Service / ServiceImpl / Mapper / Entity 分层和 MyBatis-Plus `ServiceImpl`，重建校园物品模型。 |
| `sys-project-api/.../auth/AuthService.java`、`AuthStore.java`、`RedisAuthStore.java` | 参考实际是 Redis 中的不透明会话，不是 JWT。改为签名 JWT + MySQL 会话表，保留即时注销与有效期；Redis 和验证码依赖不迁入。 |
| 旧商品买卖、订单、菜单分配、Banner、微信账号、举报等模块 | 未直接迁入。交易语义改为需求交换；完整交换、个人资料、权限细分、审核举报是后续任务。 |
| 旧 Druid、MySQL 8.0.30 驱动、Fastjson、Hutool、Kaptcha、Knife4j、Spring AI、OSS/JAXB | 当前链路未使用的库不迁入。使用 Boot 管理的 HikariCP、MySQL Connector/J、Jackson；新增 Flyway 实现版本迁移。无外部 AI 或 OSS 运行依赖。 |

## 依赖与运行

- 验证工具链：JDK 17.0.20.1、Maven 3.9.16；POM 强制 Java 17 主版本、Maven 3.9.16。Wrapper 入口见根 README。
- JJWT 0.12.5 来自参考 POM 版本，新增实际使用。`jjwt-impl`、`jjwt-jackson` 保留 runtime 依赖。
- Boot 3.4.3 管理 MySQL Connector/J 9.2.0、Flyway 10.20.1、Lombok 和测试库版本。MySQL 使用真实驱动；H2 仅在 test scope。
- 认证无需 Redis。JWT 有 8 小时到期时间，每次受保护请求还检查 MySQL 会话与用户状态。POST logout 删除会话，已签发 JWT 随即失效。
- 当前 Flyway 10.20.1 会提示 MySQL 8.4 / H2 2.3 高于它声明的已测试版本范围。保留参考 Boot 版本管理，已进行两种数据库的实际迁移和集成验证；该提示保留在验证记录，后续依赖升级需单独验证。
- 本地上传是当前唯一已实现的存储适配。OSS 尚未实现，不能通过填写旧 OSS 参数使其自动工作。
- 自动化测试移除 Mockito 运行依赖，使用真实 Spring、Mapper、Flyway 和隔离数据库，不依赖 JVM 动态自附加。

## 配置与初始化

正常运行读取进程环境变量，Spring 不会自动读取仓库 `.env`。根 `scripts/run.mjs` 是真实加载入口；直接 Java 启动时调用者须先导出变量。

| 变量 | 说明 |
| --- | --- |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | 默认 `127.0.0.1` / `3308` / `campus_loop_dev`；独立于参考工程。 |
| `DB_USERNAME` / `DB_PASSWORD` | 用户默认 `campus_loop`；口令必填，无代码默认口令。 |
| `JWT_SECRET` | 必填，至少 32 UTF-8 字节；只由后端读取。 |
| `SERVER_PORT` / `UPLOAD_DIR` | 默认 `8088` / `../.local/uploads`（相对后端启动目录）。根脚本传入确定路径。 |
| `CORS_ORIGINS` | 逗号分隔的公开前端 origin；默认 localhost/127.0.0.1 的 5174、5175。 |
| `CAMPUS_BOOTSTRAP_ENABLED` | 默认 false。仅初始化命令开启。 |
| `CAMPUS_BOOTSTRAP_ONLY` | 默认 false；开启时初始化完成后关闭 Spring，不常驻监听。可配合 `SPRING_MAIN_WEB_APPLICATION_TYPE=none`。 |
| `CAMPUS_ADMIN_USERNAME/PASSWORD`、`CAMPUS_USER_USERNAME/PASSWORD` | 初始化通过本地交互配置；不同账号；不会写入仓库、文档或日志。重复执行不重设已有账号口令。 |
| `CAMPUS_DEMO_ENABLED` | 初始化期间默认 true，加载虚构演示数据；普通启动即使为 true 也不会加载。 |

V1 建立账号、分类、物品、会话、上传表；V2 建立下一阶段交换、参与者、唯一占用、履历表。Flyway 校验已应用脚本的校验和；`clean` 禁止，脚本无 DROP 或清空操作。改变已应用表结构需新增版本。

`db/demo-data.sql` 是初始化显式调用的 insert-only 数据，不是生产启动自动播种。3 名虚构拥有者没有密码哈希，不能登录；6 件物品形成双方与三方交换例子。重复运行不覆盖已有记录。演示用户使用保留 ID 1001–1003，物品使用 2001–2006；初始化遇到被其他账号占用的用户编号会失败，防止物品归属错配。建议在新建的独立开发数据库使用演示初始化。

## 验证入口与边界

在 `sys-project` 运行 `./mvnw -B -ntp verify`（Windows `mvnw.cmd`）。默认是内存 H2，不会回退到日常 MySQL。测试用动态高优先级属性同时锁定 DataSource 与 Flyway 的连接配置，覆盖机器已有的 `SPRING_DATASOURCE_URL` / `SPRING_FLYWAY_URL`。8 项纯匹配测试 + 6 项数据库集成测试；集成验证真实 Controller、Service、Mapper 和 Flyway，包括登录、发布、SQL 读回、管理权限、注销、过期、上传内容与归属、重复种子、推荐不占用物品。

真实 MySQL 测试用 `-Dcampus.mysql-test=true`，只接受明确提供的 `TEST_DB_URL`、`TEST_DB_USERNAME`、`TEST_DB_PASSWORD`。URL 被限制为 localhost / 127.0.0.1、显式端口、`campus_loop_*test` 库名。测试会新增随机账号、物品和演示数据；使用独立临时库，数据库销毁由隔离服务管理，不在测试中清空任意已有库。测试上传只写操作系统临时目录并清理。根脚本提供服务生命周期入口。

H2 测试通过不等于真实 MySQL、浏览器或微信环境已验证；本轮实际结果见根验证记录。
