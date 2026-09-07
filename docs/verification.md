# 验收记录

验收日期：2026-09-07。以下按层记录，不能把构建成功替代浏览器或微信真机验证。

| 层次 | 当前证据 |
| --- | --- |
| 工具版本 | macOS arm64，Node24.20.0、npm12.0.2、Temurin17.0.20.1、Maven Wrapper3.9.16实际运行 |
| 管理端 | npm ci与Vite8.2.2生产构建通过；控件页浏览器交互与响应式已执行 |
| UniApp | npm ci、H5及mp-weixin构建通过；H5实际登录、上传、发布、详情刷新与退出验证通过 |
| 后端默认测试 | 8项纯匹配测试 + 6项Spring/Flyway/Mapper集成测试通过，H2完全隔离 |
| MySQL测试 | scripts/mysql-test.mjs随机口令临时MySQL8.4.11容器，14项测试通过，运行后停止测试容器 |
| 开发数据库 | 独立campus_loop_dev/3308初始化成功；Flyway2个版本迁移；示例发布、读取、管理员查看与退出撤销14项API检查通过 |
| 管理端浏览器 | 桌面及375/390/768像素无横向溢出；浅/深色、输入错误恢复、日期、选项、分页、通知、弹窗/抽屉快速反向检查 |
| 干净clone | 从提交内容clone到全新目录：npm ci、三种前端构建、后端14项测试、另一独立MySQL初始化与14项API链路检查通过；生成manifest无需预先存在 |
| GitHub | 目标私有仓库429res/campus-loop，main；当前CI结果见[仓库Actions](https://github.com/429res/campus-loop/actions/workflows/ci.yml)（包括Linux和Windows构建），组织Free不支持私有分支强制保护 |
| Windows | 提供mvnw.cmd、跨平台脚本与Windows CI完整构建；本机未运行Windows桌面或Docker Desktop，云端结果以Actions为准 |
| 微信 | 小程序编译通过；未使用真实AppID，未执行开发者工具模拟器或真机联调 |

## 实际修复

浏览器检查发现Element Plus多个弹层快速切换可能残留body滚动锁，已改为公共引用计数锁，验证关闭后html/body无残留锁与可见遮罩。UniApp H5原生button的键盘语义不足，使用共享可访问按钮组件适配；微信保留原生实现。

测试隔离防止环境变量覆盖：即使外部SPRING_DATASOURCE_URL、SPRING_FLYWAY_URL存在，测试也强制使用已校验的H2或明确的本机campus_loop_*test连接。Windows脚本保留原生参数边界，避免带空格路径被shell拆开。

CI 的 `setup-java@v4` 不接受四段 Java 版本。已按 [Temurin 官方发布元数据](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz.json)使用精确 SemVer `17.0.20+101`（对应实际 `17.0.20.1+1`），Linux/Windows 均额外检查运行时版本和 Eclipse Adoptium 厂商；JDK 基线没有降级。修复后云端执行结果以 Actions 为准。

## 边界与已知提醒

- 匹配上限200件AVAILABLE候选，超过返回422；正式规模化需候选分区。
- Flyway10.20.1对MySQL8.4输出“更新支持版本”的提醒；本次独立MySQL8.4.11迁移与集成测试实际通过。依赖升级需单独兼容性PR。
- 管理端完整引入Element Plus的生产包会有chunk体积提醒；构建成功。后续可按需拆包，避免初始化时盲目升级。
- API与浏览器示范有意保留两个虚构发布记录及测试图片（主开发库#2007、#2008），用于刷新与后台查看。它只存在本机开发库/上传目录，不进入Git；每次再次运行会再新增一条，不是无写入健康检查。
- 当前Exchange和履历为数据/接口设计与明确待开发入口，没有完整交易执行能力。

## 跨端浏览器和数据库证据

H5用本地生成的学生账号登录，原生选择分类/成色/需求、上传PNG、发布#2008；详情硬刷新仍显示同一标题和已加载图片。管理端本地管理员登录后搜索“浏览器验收 · 校园循环”，详情确认同一id、标题、标签和需求。退出后刷新保持未登录。未输出任何账号口令或token。

主开发库只读核对：5名用户（3名虚构演示者+2个本地账号）、8件物品、2个成功迁移；cl_item_hold=0、cl_exchange=0，推荐读取没有建立交换或占用。

H5六路由在375px与768px均无横向溢出，详情另行同尺寸验证；模态连续开关5次、抽屉6次，无阻挡遮罩，键盘焦点可返回。两个前端的应用内减少动态效果开关实测过渡0s；OS媒体查询使用共同降级规则，但未修改本机系统偏好，不宣称已切换原生OS设置。

## 依赖审计边界

2026-09-07 npm audit发现继承的UniApp工具链65项依赖告警（14高、17中、34低，无critical；包含传递/元依赖重复传播，非65个独立漏洞）。保持Vite5.2.8和匹配的DCloud锁定版本以保证本次H5/微信构建兼容；专项升级在路线图中列为外部部署前要求。开发服务只监听回环地址，不对校园网络开放旧开发服务器。未执行会跨版本破坏适配的audit fix --force。管理端可兼容修复的Axios告警已在迁移说明中记录兼容的1.x安全更新（1.13.2→1.20.0，更新后管理端audit为0）。
