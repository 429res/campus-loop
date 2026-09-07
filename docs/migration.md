# 参考工程迁移记录

参考工程仅作只读检查，不携带其 Git 历史、依赖目录、构建产物、日志、真实上传、凭据或业务数据。新工程在独立目录中重建业务。以下为明确的技术复用范围；未把完整旧业务复制后改标题当成迁移。

| 参考实现 | 处理 | 原因 |
| --- | --- | --- |
| 两前端 package.json + package-lock.json | 以原锁文件为起点保留依赖和构建器，调整包名/脚本 | 管理端 Vite8、UniApp Vite5分别适配 |
| 管理端 Axios、Pinia会话/路由拦截 | 适配新字段、Bearer令牌、角色与错误 | 原认证接口及品牌不同 |
| SysDialog、UploadImage、Liquid Glass公共样式 | 保留有效模式，重设语义变量、端点与控件覆盖 | 新品牌和统一交互体系 |
| UniApp http/modal/materials/motion | 适配新API、主题和原生控件策略 | 复用超时、错误、原生桥接与降级 |
| 后端 com/api、controller/service/mapper/entity | 保留结构，Java包调整为 edu.campusloop | 新业务边界 |
| ResultVo、异常处理、校验、MyBatis-Plus分页 | 保留契约思路并简化字段实现 | 统一HTTP与业务错误，隔离旧业务 |
| BCrypt密码校验、认证拦截和会话检查 | 改为JWT+数据库撤销会话 | 参考实际为Redis不透明令牌；新项目不需Redis，保留真正退出能力 |
| 上传安全检查与配置模式 | 本地可配置存储，验证文件类型、大小与路径 | 原上传/OSS配置含个人部署信息，不迁入 |
| Spring Boot3.4.3 / MyBatis-Plus3.5.5 | 保留；新增Flyway，驱动与连接池采用Boot管理 | 需要版本化迁移，移除旧Druid集成负担 |
| 原菜单、横幅、支付/订单、Miku资产及组件 | 未迁入 | 不属于当前校园交换基础；新业务按契约开发 |
| 原微信AppID、数据库、OSS、JWT配置 | 不迁入 | 本地环境变量与空公开示例代替 |
| 旧SQL及真实数据 | 不迁入 | 独立Flyway结构与虚构幂等种子 |

更精确的文件对应和依赖变化见 [后端迁移](backend-migration.md)、管理端与UniApp子目录 `docs/migration.md`。如果表述与源码不同，必须核对具体实现并更新记录，不声称没有执行的运行验证。
