# Campus Loop 视觉与控件

研究于2026-09-07：[bilibili 首页](https://www.bilibili.com/) 在实际浏览器中可见顶部搜索与账号入口、水平分类、图片优先卡片、标题与发布者的分层；转化为校园搜索、分类、物品和交换需求的顺序。保留年轻、亲切、内容清晰的特点，重新设计品牌、布局和资产，不复制商标、吉祥物或整页。

参考 [Apple Materials](https://developer.apple.com/design/human-interface-guidelines/materials) 和 [Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/)：控件与导航形成内容上方的功能层；正文保持稳定。这里实现的是 Web/小程序透明、柔化、高光和动效的材质近似，不宣称 Apple 原生光学折射。

## 共用 token

`shared/design-tokens.css` 是语义变量入口，两端公共样式映射组件库和原生控件。背景白/浅灰，文本深蓝灰；粉色主要行动、蓝色链接及选中。浅色主文字 #192333，次文字 #596578，粉色 #ca376f、蓝色 #2774c7；深色用暗背景和更明亮的前景。不能只反转图片或给整个页面加透明度。

焦点、遮罩和禁用态同样使用共享语义变量：`--cl-focus/--cl-focus-soft`、`--cl-overlay`、`--cl-disabled-opacity`。UniApp 原生导航、switch、slider 与系统弹窗无法直接读取 CSS 变量时，只在公共组件或平台配置中镜像同一色值，业务页面不再各自硬编码。

字体使用系统无衬线，不依赖外部字体服务。字号层级：页面标题28–32、区块20–24、正文14–16、辅助12–13；正文行高1.5–1.7。间距4/8/12/16/24/32，控件圆角12、面板18。图标含明确语义或可访问名称；触摸区域至少44px，桌面密集表格行中允许32px小按钮并留间距。

控件阴影小而柔和，按钮不使用强白边或厚内阴影。卡片图像、表格和长表单采用稳定实色底，不为数十张物品卡片同时开启背景模糊。本地 SVG 是清楚的示意图而非真实商品摄影；上传图像来自用户本机。

## 材质、动效与状态

- 公共材质：半透明底 + 一层边缘高光 + 柔影；弹层外壳只绘制一次，内部区域不叠加第二层玻璃。
- 反馈180ms，选择240ms，面板320ms，公共缓动；业务状态立即改变，用 CSS transition 支持中断、反向，避免定时器延迟清理遮罩。
- hover、focus-visible、pressed、selected、loading、disabled、error 均有明确反馈。焦点可见且不只靠颜色；错误文案与输入关联，加载中阻止重复提交。
- 分段与导航指示器连续移动；Popper 根据触发位置展开，模态支持 Escape、焦点限制与返回；抽屉与遮罩一起收尾。快速连续开关后不得留下滚动锁和遮罩。
- `prefers-reduced-motion` 去除大幅位移；不支持 backdrop-filter、低性能或减少透明度模式使用稳定实色与细边框。微信原生导航、TabBar、picker、系统授权和弹窗遵循平台能力。

## 覆盖与展示入口

管理端 `/controls` 展示完整 Element Plus 控件；用户端有控件实验室入口和真实首页/发布/登录页面。对应细节与映射表见 `project-self/docs/liquid-glass-controls.md`、`wx-project-self/docs/liquid-glass-controls.md`。

| 类型 | 管理端 | H5 / 微信处理 |
| --- | --- | --- |
| 导航、侧栏、Tab、分段 | 公共材质、移动选中指示器 | 可控导航共享 token；微信原生 TabBar |
| 按钮、图标按钮 | 含 loading/disabled/danger | LoopButton 显式转发 click/form-type/loading/ARIA；微信以 wx://form-field-button 关联外层表单；H5 支持单次 Enter/Space 激活 |
| 搜索、输入、选择、日期 | 焦点/错误/清空/下拉统一 | 原生 input/picker，H5 弹层公共材质 |
| 开关、分页、上传 | 状态过渡、防重入、移除同步 | 原生 switch/照片选择和列表翻页 |
| 菜单、tooltip、通知 | Popper 外壳单层玻璃 | H5 提示与菜单；微信 toast/action sheet |
| 弹窗、抽屉 | 可中断进出、Escape、焦点管理 | H5 共享控制层，微信原生 modal |
| 内容、图片、表格、表单 | 实色阅读表面 | 实色卡片和表单，内部控件继承规范 |

新增控件时登记覆盖清单，不能仅靠选择器存在就声称交互完成。验收需覆盖浅/深色、手机375px、平板768px、桌面、键盘、快速弹层开关、减少动态效果、失败与禁用状态。构建与浏览器/微信真机结果分别见验收记录。
