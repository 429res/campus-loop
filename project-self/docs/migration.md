# 管理端迁移记录

本目录是 Campus Loop 新工程，参考项目只读检查后按业务边界迁移。未复制原仓库历史、node_modules、dist、上传文件、本地环境文件或业务数据。

| 原实现 | 本项目处理 | 原因 |
| --- | --- | --- |
| Vue 3、Vite 8、Element Plus、Pinia、Router、Axios 及 package-lock.json | 保留原依赖声明与锁定版本；仅改包名及根目录约定的 engines/packageManager | 避免改变已适配的工具链；UniApp 保持独立版本 |
| `src/http/index.js` | 保留 Axios 实例、Bearer 请求拦截、超时、401 失效与统一错误提示模式，调整 API 地址/会话键/返回检查 | 新项目同源代理及独立后端，无旧项目端口/缓存键 |
| 用户 Pinia store | 保留 sessionStorage 会话恢复、刷新用户和清除逻辑；调整为 `id/username/displayName/role` | 当前 ADMIN/USER 契约；细粒度菜单权限后续实现 |
| `SysDialog.vue` | 迁入具名内容槽、尺寸/加载/禁用、确认与取消事件，重绑语义 token | 保留公共弹窗结构；修复快速切换时滚动锁竞争 |
| `UploadImage.vue` | 迁入文件类型/体积验证、可取消上传、卸载终止、受控图片列表及移除同步 | 上传改为 `/api/uploads`、`data.url`；与后端一致限定 JPG/PNG/GIF |
| `liquid-glass.css` 和控件规范 | 迁入完整材质、状态、Popper 方向、动效及降级层，映射共享 `--cl-*` 变量 | 新品牌统一视觉；不迁入角色主题、角色图片或装饰 |
| 原菜单/广告/旧用户/商品管理等业务页 | 未迁入，按 Campus Loop 契约新建概览、物品、推荐及明确待开发页 | 避免旧数据模型、旧路由与假功能混入初始工程 |
| 旧 API 模块及权限码树 | 未迁入；新接口集中使用统一 Axios 包装器 | 后端模型与权限边界不同 |

新增 `src/composables/useOverlayLock.js` 管理自己的 `html.cl-overlay-lock`，每个弹窗/抽屉使用引用计数。对应 Element Plus `lock-scroll` 关闭，避免其每实例延迟释放导致快速开关后残留锁；不会删掉其他组件的锁或覆写其 inline overflow。离场遮罩不拦截新的触发点击，支持动画被反向打断。

页面 `/controls` 无需登录可预览，上传在未登录时禁用；真实管理数据页面必须持有后端签发的管理员会话。退出调用 `/api/auth/logout`，由后端撤销会话。

公开环境变量仅包括 `VITE_API_BASE_URL` 和开发代理 `VITE_API_PROXY`。Vite 开发端口 5174，代理 `/api` 与 `/uploads` 至新后端 8088。部署静态构建时由同源网关转发 API/上传，或在构建前显式配置公开 API 地址；`vite preview` 不代表生产 API 网关。

原创占位 SVG 位于 `public/demo/`，与用户端共享相同图形，图形只表达书籍/相机/台灯/自行车，不引用外部链接。
