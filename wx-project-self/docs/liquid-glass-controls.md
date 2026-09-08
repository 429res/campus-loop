# H5 与微信控件规范

设计 token 来自根目录 `shared/design-tokens.css`；材质、状态和动效来自 `src/styles/`。正文、商品图片和长表单使用稳定底色，玻璃仅用于导航与小面积控件。这是 Web / 小程序材质近似，不是 Apple 原生光学折射。

开发入口：我的 → 控件实验室，或 H5 `/#/pages/controls/controls`。

| 控件 | 公共实现 / 平台边界 |
| --- | --- |
| 导航、Tab | H5 `LoopLayout` 的可键盘导航与移动选中块；微信保留原生 navigationBar / TabBar |
| 分段选择 | `LoopSegment`，连续位移，可反向打断，选中状态可访问 |
| 按钮、图标、加载、禁用 | `LoopButton`；H5 明确 role/tabindex，native KeyboardEvent 支持 Enter/Space |
| 输入、搜索、长文本、错误 | 公共输入材质、焦点环、错误边框及独立错误文案 |
| 选择、日期 | `LoopPicker`：H5 使用浏览器原生 select/date，微信使用原生 picker；不重绘系统日历 |
| 开关、滑块 | `LoopSwitch` / `LoopSlider`，H5 补充键盘与可访问语义，微信原生交互 |
| 分页 | 共用按钮；边界禁用、页码可见 |
| 上传 | 原生文件/相册选择，共用选择/移除按钮；上传期间阻止重复提交；PNG/JPEG/GIF 最大 5 MB |
| 操作菜单、通知 | UniApp 原生 actionSheet / toast，H5 单层外壳统一材质，微信保留系统能力 |
| 弹窗 | `showAppModal` 单入口；H5 焦点与键盘、关闭恢复；微信原生 |
| 抽屉 | `LoopSheet`，持续存在的过渡外壳、关闭后 inert 与 pointer-events:none、Escape、Tab 圈定与焦点返回 |

H5 开关/按钮的 UniApp 通用事件会丢失键盘字段，因此 `useKeyboard` 绑定原生 KeyboardEvent，而非假设有 tabindex 就已可键盘操作。`LoopPicker` 使用真正的浏览器 select/date 避免触摸滚轮阻碍键盘操作。

系统 `prefers-reduced-motion` 与控件页“减少动态效果”开关使用同一 Sass 降级逻辑。H5 低并发硬件自动 `data-glass=reduced`，滤镜不支持、减少透明度/高对比时使用实色。微信全部采用实色默认值。浅深色均保留可读正文与清晰焦点。

新增页面优先复用公共组件。当前没有侧栏的用户端不额外制造侧栏；管理端侧栏、表格等覆盖见根设计系统文档。微信原生授权、相册及系统导航的最终行为仍需开发者工具与真机验证。

本人资料与修改密码复用公共输入和按钮。只读用户名使用原生禁用态；保存、改密有独立 loading/disabled/error 状态。密码仅存在当前页面内存，收起表单、响应完成或离开登录态时清空；确认弹窗沿用 `showAppModal` 的 H5 焦点圈定与微信原生弹窗。资料写请求未收到响应时禁用再次保存，须先从服务端重新查询；改密结果不确定时清除本地会话并转到登录页确认。
