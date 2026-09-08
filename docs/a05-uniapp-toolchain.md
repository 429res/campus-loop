# A-05 UniApp 工具链依赖说明

## 本批边界

本切片只调整 `wx-project-self` 的工具链依赖和锁文件，不改业务代码、管理端、后端或部署配置。DCloud 5.24 正式版依赖保持统一版本 `3.0.0-5020420260813003`，H5 与 mp-weixin 继续使用各自既有命令和产物目录。

## 兼容依据

- DCloud [正式版更新日志](https://uniapp.dcloud.net.cn/release)当前正式版为 5.24.2026081301；正式版记录明确 Vue3 编译器使用 Vite 5.2.8。
- DCloud [CLI 快速上手](https://uniapp.dcloud.net.cn/quickstart-cli.html)区分正式版与 Alpha 版升级，并说明 `uvm` 只更新主要编译器依赖，新增脚本仍需人工核对。本批不切换 Alpha。
- 已安装 `@dcloudio/vite-plugin-uni@3.0.0-5020420260813003` 的 peer 为精确的 `vite@5.2.8`。因此 Vite 不能单独升级；Rollup 的修复版本 4.63.1 仍满足 Vite 5 的 `^4.13.0` 范围。
- npm 12 的 [安装脚本策略](https://docs.npmjs.com/cli/v11/commands/npm-install-scripts/)支持在 `package.json` 逐包批准或拒绝。这里只批准构建必需且已锁定的 esbuild 安装脚本；core-js 的提示脚本明确拒绝。

## 实际依赖变化

| 类型 | 变化 | 理由 |
| --- | --- | --- |
| 直接 | `rollup` 4.14.3 → 4.63.1 | 同主版本安全补丁，修复审计报告中的两个 Rollup 高风险公告 |
| 直接 | 移除 `@dcloudio/uni-automator` | 项目源码、脚本和验收均未调用；移除其 Jest 27/jsdom 16 旧测试链，不影响 H5/微信构建器 |
| 传递覆盖 | Intlify 9.1.9 → 9.14.5 | 保持 9.x API，统一到项目已有 Vue I18n 9.14.5 的依赖族 |
| 传递覆盖 | Express 4.20.0 → 4.22.2，qs → 6.16.0 | 保持 Express 4/qs 6，修复旧请求解析链告警 |
| 传递覆盖 | PostCSS → 8.5.28 | 保持 8.x，修复 nvue 样式器锁入的旧补丁版本 |
| 传递覆盖 | ws 8.18.0 → 8.21.3 | 保持 8.x，修复小程序编译链的内存泄露与拒绝服务公告 |
| 安装策略 | 允许 `esbuild@0.20.2`；拒绝 `core-js`、`core-js-pure` 提示脚本 | npm 12 默认拦截未审查脚本；esbuild 需要安装平台二进制，core-js 两项只输出资助提示 |

锁文件重装后依赖总数由 996 降至 603。`npm ci` 仍会显示 `phin` 2/3 和 Vue I18n 9 的弃用提示；它们分别来自 DCloud 的 Jimp 平台链及当前正式模板兼容线，未伪装为已解决。

## 审计结果与剩余风险

| 快照 | critical | high | moderate | low | total |
| --- | ---: | ---: | ---: | ---: | ---: |
| 升级前，2026-09-08 显式联网 | 0 | 14 | 17 | 34 | 65 |
| 升级后，2026-09-08 显式联网 | 0 | 4 | 10 | 25 | 39 |

剩余 4 个 high 节点为 `@dcloudio/uni-cli-shared`、`adm-zip`、`jpeg-js` 和 `vite`。它们由以下不可在本批安全跨越的边界产生：

- DCloud 正式版把 Vite 5.2.8、esbuild 0.20.2、Babel 7.25.2、adm-zip 0.5.16 固定在编译器包中；当前审计建议会破坏精确 peer 或跨越 0.x 兼容边界。
- 微信/百度平台包的 Jimp 0.10 链要求 `jpeg-js ^0.3.4`，安全版本在 0.4.x；直接覆盖会跨越其允许范围。
- npm 给出的完整修复方案均标记为 breaking，并会把 DCloud 包降到无关旧版本，不能采用。

下一步是在 DCloud 发布包含新 Vite/Jimp 链的正式 Vue3 CLI 组合后，用同一方法整体升级全部 `@dcloudio/*` 编译器包，再重新执行双端构建、联网审计和 D 的平台验证。开发服务器不得对校园网络开放，以降低现有 Vite 开发服务公告的暴露面。

## 本机验证与 D 交接

本机已执行：

```text
npm ci --offline=false
npm test
npm run build:h5
npm run build:mp-weixin
npm run test:mp-build
npm audit --json --offline=false
node scripts/repository-check.mjs
```

D 需在微信开发者工具导入 `wx-project-self/dist/build/mp-weixin`，使用隔离测试后端与非真实业务数据验证：登录并刷新会话；选择图片、上传及取消/失败恢复；填写分类、成色和需求后发布；公共按钮的点击、禁用、加载、表单提交与可访问标签。随后至少在一台真机复核登录、选择图片/上传、发布与主要导航。不得提交 AppID、令牌、账号或截图中的敏感数据；未完成时 PR 必须保持“微信工具/真机未验证”。
