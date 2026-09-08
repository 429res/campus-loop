# Campus Loop 管理端控件覆盖

入口：`/controls`。主题语义来自仓库 `shared/design-tokens.css`，Element Plus 映射与页面布局在 `src/styles/base.css`，统一材质和动效在 `src/styles/liquid-glass.css`。不依赖私人技能或绝对本机路径。

| 控件 | 公共实现/验证入口 |
| --- | --- |
| 导航、侧栏、账户入口 | App.vue；选中底板连续移动、手机收起与 Escape、键盘焦点 |
| Tab、分段、单/复选 | Element Plus + 统一 indicator/selected 状态；实验室 01/04 |
| 按钮、图标按钮 | 统一轻边缘与按压；实验室 02；禁用/加载不会提交业务请求 |
| 搜索、输入、多行、错误 | 稳定输入底色，焦点与错误边缘；实验室 03、登录表单 |
| 选择器、日期、开关 | 单层 Popper、方向感知展开、原生键盘支持；实验室 04 |
| 分页、上传 | 统一按压、焦点与禁用；实验室 06。上传会真实调用接口 |
| 下拉、提示、气泡 | 单层玻璃外壳与触发点方向；实验室 05 |
| 消息、通知、确认框 | 单层小面积材质，关闭与自动消失；实验室 02/05 |
| 弹窗、抽屉 | SysDialog / Element Drawer + useOverlayLock；实验室 05、物品详情 |
| 商品、表格、长表单 | 固定内容底色，内部操作沿用控件规范；不对每张卡片实时模糊 |
| 物品审核表单 | Items / ReviewDecisionDialog；开发环境 `/fixtures/item-review` 覆盖理由错误、loading/disabled、403、409回读和失败保留。生产不注册夹具路由，写接口未合入前正式按钮禁用 |

所有控件共享悬停、聚焦、按下、选中、加载、禁用、错误、打开/关闭的相应状态。表格本身保持阅读效率。原生按钮和 Element Plus 保留键盘语义与焦点环，切换主题保持明确语义色；深色填充主按钮使用深色文字以保持对比。

动画采用可逆 CSS transition。浮层根据 `data-popper-placement` 选择展开边，离场遮罩设为不接收指针，防止快速重新打开时吞点击。弹窗和抽屉必须使用公共锁服务并关闭 Element Plus 的单实例 `lock-scroll`；不得另行强制清除 body 的其他锁。

降低开销：只对导航/小浮层使用 blur；设备核心数低、低内存或节省流量时自动 `data-glass="reduced"`，也可在实验室手动简化。无滤镜支持、减少透明度或高对比偏好时使用稳定底色。系统 `prefers-reduced-motion` 始终生效；实验室「减少动态效果」通过 `html.cl-motion-reduced` 使用等效规则并保存本地偏好。两者都将动画/过渡时长降为 0，移除明显按压/悬停位移。

这是 Web 的材质近似，不是 Apple 原生光学折射。微信原生控件规范见用户端文档。

## 本轮浏览器检查

2026-09-07，Codex 内置浏览器：

- 桌面 1280×720、手机 390×844、平板 820×1180 检查；手机和平板 document scrollWidth 等于 viewport，无横向页面溢出。
- Tab/分段移动、必填错误消除、分类选择、日期选择、分页、菜单、消息与通知均实际操作；登录页空提交显示真实必填错误。
- 弹窗和抽屉各连续开关 4 次、混合 Escape→重开→取消→抽屉→关闭→重开；修复后最终无可见遮罩、html/body 无遗留锁，overflow 恢复 visible。
- 深色实际切换；修复 Element Plus 暗色默认变量优先级后，主色仍为 Campus Loop 粉色，内容底色遵循共享 token。
- 简化材质开关实际检查 topbar `backdrop-filter: none`。
- 减少动态效果开关实际检查 dialog、drawer、导航与分段指示器的 computed transitionDuration 均为 `0s`；刷新后偏好仍生效、关闭后无残留遮罩。
- 系统减少动态效果 CSS 分支已检查；未修改用户操作系统的辅助功能设置。自动化/构建、浏览器 UI 和真实数据库链路应分别记录，不相互替代。
