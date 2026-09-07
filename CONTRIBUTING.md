# 四人协作

所有成员均应能独立 clone、初始化自己的数据库、运行三端并理解完整链路。职责表示主负责人，不是目录权限边界。仓库 private，仅组织成员访问；组织默认 Read 不代表拥有写权限，需仓库维护者给准确的成员或组织团队 Write 权限。

| 角色 | 主责 | 第一批交付 |
| --- | --- | --- |
| A | 后端基础、认证权限、迁移、工程环境 | 账号生命周期、细粒度权限、独立环境、迁移兼容性 |
| B | 需求匹配、交换、履历、后端测试 | 独立需求清单、事务创建、并发占用、状态机与履历来源 |
| C | 管理端、视觉规范、公共控件 | 分类、审核、举报、统计页面与全控件验收 |
| D | UniApp H5/微信交互 | 搜索/收藏、需求清单、邀请确认、真机与无障碍验证 |

A 与 B 共同审核数据模型、事务和鉴权；B 给 C/D 提供可解释的接口样例及失败状态；C 与 D 共用设计 token 与控件覆盖清单。任何成员可以提交其他模块修复，并请主负责人复核。

## 分支与 PR

`main` 保持可运行；新增使用 `feature/<简短主题>`，修复使用 `fix/<简短主题>`。一个 PR 聚焦一项可验收功能，不提交依赖目录、构建产物或 `.env`。

```sh
git switch main
git pull --ff-only origin main
git switch -c feature/demand-list
# 修改、验证
git add <本次文件>
git diff --cached
git commit -m "feat: add demand list validation"
git push -u origin feature/demand-list
```

在 GitHub 创建 PR 指向 main，填模板、关联 Issue，选择至少一名其他成员审核。提交消息推荐 `feat:` / `fix:` / `docs:` / `test:` / `chore:`。PR 必须写结果、接口影响、迁移影响和实际验证；未验证项明确标注。

## 同步与冲突

先提交或暂存自己的工作，再 `git fetch origin`、在功能分支 `git merge origin/main`。发生冲突时阅读两侧意图，和改动者沟通，逐文件合并后运行受影响检查、`git add`、`git commit`。不确定可 `git merge --abort` 返回合并前。不要用整文件 ours/theirs 掩盖冲突，也不要 force push main。

多人共用的功能分支优先 merge；只有自己的分支且团队同意时才 rebase，需要推送改写历史时用 `--force-with-lease`。合并完成回 main 拉取，然后删除已合并的本地功能分支。

## 共享文件协商

修改根脚本、CI、两端 lockfile、shared token、API 契约、公共组件或数据库迁移时，先在关联 Issue/PR 简述范围并通知受影响负责人；数据库版本号同日冲突时由 A 协调新序号，已被团队运行的迁移不改写，新增修正迁移。禁止复制其他人的 `.env` 来解决环境问题。

跨模块功能先提出请求/响应、错误和状态草案，由消费端确认，再实现；破坏性变更在同一 PR 同步所有调用方。接口样例只能用虚构身份和物品；截图不能带 token、口令、个人联系信息。

## 团队约定与平台强制的区别

2026-09-07 实查 `429res` 套餐为 **Free**。GitHub Free 组织的私有仓库不提供强制分支保护；保持私有，不擅自升级付费或公开。详见 [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。

因此本仓库的“至少一名其他成员 Approve、所有 CI 成功、讨论已解决、禁止直接提交 main”目前是**团队约定**，不是平台阻断保证。维护者合并前手动核对；PR/Issue 模板和 CI 负责提供证据。后续组织若获得 Team/Enterprise，启用 main 保护：1 个其他成员审核、过期审核失效、要求分支最新、所有独立 CI 检查通过、解决讨论、禁止强推/删除，并对管理员同样生效。

尚未提供其他三位成员的准确 GitHub 用户名，所以不会猜测或批量邀请组织成员。邀请已发出、已接受、组织成员资格和仓库 Write 权限是不同状态，需逐一核实。
