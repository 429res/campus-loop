# 用户端迁移记录

参考项目只读。迁移前检查了 `package.json`、锁文件、`src/common/http.js`、`src/common/modal.js`、`App.vue`、`main.js`、`uni.scss`、manifest、材质与动效样式及控件规范。

| 参考实现 | Campus Loop 处理 |
| --- | --- |
| `package.json` 与 `package-lock.json` | 保留全部依赖与锁定解析；UniApp `3.0.0-5020420260813003`、Vite `5.2.8`，不跟随管理端升级 |
| `src/common/http.js` | 复用超时、返回封装检查、失败反馈、过期会话保护与上传解析；改用 Bearer 和独立存储键 |
| `src/common/modal.js` | 保留微信原生弹窗与统一 H5 外壳；补充 H5 焦点、Enter/Space/Escape 与重复打开保护 |
| `src/styles/materials.scss`、`motion.scss` | 延续材质/动效分离、实色优先、可中断过渡及减少动态效果，重新绑定共享 `--cl-*` token |
| Miku 页面、图标、角色、主题数据 | 未迁入；重建 Campus Loop 原创粉蓝校园身份 |
| `src/uni_modules/vk-uview-ui` | 未迁入；本次页面用 UniApp 原生控件及轻量共享封装，不需要其组件运行时 |
| 原业务商品数据、旧 API 与旧认证存储 | 未迁入；所有业务读取新项目 API，未实现模块明确显示待开发 |
| 原真实 AppID、本地凭据、上传、依赖、dist、日志、Git 历史 | 未复制 |

`src/manifest.json` 是忽略的生成文件。UniApp CLI 在 Vite 配置之前读取它，因此 npm `predev:*` / `prebuild:*` 通过 `scripts/prepare-manifest.mjs` 先生成，确实加载对应模式的 `.env`。不要绕过 npm 脚本直接执行 `uni`。

前端只使用公开配置：`VITE_API_BASE_URL`、`VITE_API_PROXY`、`VITE_MINI_API_BASE_URL`、`VITE_WECHAT_APP_ID`。H5 API 默认走 `/api`、`/uploads` 代理，微信开发工具默认访问 `127.0.0.1:8088`。真实设备请配置可访问的 HTTPS API 和微信合法服务器域名。

原创 SVG 占位素材与管理端共享相同画稿，保存在 `src/static/demo/`。PNG 导航图标为项目自产，无临时外链。

H5 启动统一使用 Vite 的 `127.0.0.1:5175` 默认值。需要并行工作区时用公开环境变量 `VITE_DEV_PORT` 覆盖端口、`VITE_API_PROXY` 覆盖后端地址；不要反复追加 `--port` / `--host` CLI 参数。

## 继承的依赖风险

初始化时对原锁定 UniApp 工具链运行的 `npm audit` 报告 65 项问题，其中 14 项 high。该快照随审计数据库变化，并不表示这些问题都可从已部署页面利用；也不构成可忽略的结论。本次为保持 UniApp 构建兼容性未执行 `npm audit fix --force` 或统一升级 Vite。

正式发布前必须逐项核对依赖链、运行时暴露及修复版本，在独立分支更新到兼容的 UniApp 工具链并重新验证 H5、微信构建和真实流程。未完成这项工作前，本初始化工程仅作为隔离开发基础，不标记为生产发布就绪。
