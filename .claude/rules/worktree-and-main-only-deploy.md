# Worktree 隔离 + 只从 main 部署 prod (HARD RULE)

**最后更新**: 2026-05-30
**触发事故**: 2026-05-30 青花椒 RBAC 修复在 prod 被并发 session 的 Java 部署覆盖, 总营收回归 ¥0。

---

## ⛔ 三条铁律 (任何代码工作都遵守)

### 1. 永远不在主工作目录直接干活

**禁止**在 `C:\Users\Steve\my-prototype-logistics`(主工作目录)直接改代码 / 跑构建 / 长任务。

每个任务开**独立 git worktree**:

```bash
git worktree add -b feat/<task> ../cretas-<task> origin/main   # 永远 off origin/main
cd ../cretas-<task>
# 全部工作在这里做
```

**Why**: 主目录被多个并发 session(Claude chat / Cursor / VSCode)共享, 互相污染 —— working-tree 改动串台、commit 范围被并发文件污染、rebase/checkout 被别 session 的未提交改动阻塞。worktree 物理隔离。

### 2. 完成 → merge 到 main (不在 feature 分支上累积部署)

任务完成 → PR → merge 到 `main`。**worktree 永远 off `origin/main`** 开(不要 off 别的 feature 分支, 否则基底会夹带 sister 的 commit → PR scope 污染, 2026-05-30 实测一个分支 PR 夹带 140 个 sister 文件)。

merge 前确认 PR scope 干净:
```bash
git diff origin/main...HEAD --stat   # 应只有你的文件, 没有 sister 的 audit/docs
```
如夹带 sister 文件 → 说明 worktree 没 off origin/main, 用 `git cherry-pick <你的commit范围>` 到干净的 origin/main worktree 重做。

### 2b. 合入通道双轨 (2026-07-28 Steve 拍板; 免 PR 三往返的网速优化)

| 变更类型 | 通道 |
|---|---|
| docs / `.claude/`(skills/rules) / 配置类, 零 CI 相关 | **fastlane 直推 main**: `./scripts/deploy/publish-main-fastlane.sh --base-sha <起点SHA> --confirm YES-DIRECT-MAIN`(先 `--dry-run` 预检)。脚本门禁: fast-forward only / 禁 force push / 推前 re-fetch 证明 origin/main 未前进。⚠️ 分支名硬性要求 `codex/*` 前缀 — Claude 侧统一用 `codex/claude-<task>` |
| 碰 backend / web-admin 代码 | **PR** — CI `JPA repository query startup gate` 挂在 PR 上; PR 号是台账/memory 的引用锚点 |
| AGENTS.md / workflows / `scripts/deploy/*` / db 迁移 / entity / repository / security | **强制 PR** — fastlane 将这些列为高风险路径自动拒绝(除非 owner 显式 `YES-HIGH-RISK-REVIEWED` 覆盖, 默认不用) |

⛔ 双轨底线不变: **任何通道都必须推上 origin/main 后才可部署**。origin/main 是多 session 唯一汇合点(本规则 5/30 事故的根治点), "本地 main 不推就部署"在任何轨道都禁止 — 省的是 PR 仪式往返, 不是 push 本身。

### 3. prod 永远从 main 构建/部署 —— 绝不从 feature 分支部署 prod

```bash
# ✅ 正确: 部署 prod 前先在 main
git checkout main && git pull origin main

# 正常 Java/Web 发布 — 统一入口 (脚本自身强制 clean HEAD == origin/main, 与本规则互为保险):
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<tests>' --confirm-prod YES-PROD

# 单组件/排查入口:
./scripts/deploy/deploy-backend.sh --env prod        # Java
./scripts/deploy/deploy-smartbi-python.sh --env prod # Python
./scripts/deploy/deploy-web-admin.sh --env prod      # web-admin
```

**禁止**从 feature 分支直接部署 prod。

**Why (致命)**: `deploy-backend.sh` 上传到**固定的共享 jar 路径** (`s3://cretas/deploy/cretas-backend-system-1.0.0.jar` + 服务器固定 jar)。多个 session 各自从自己的 feature 分支部署 prod = **last-write-wins 互相覆盖**。2026-05-30 实测: 我从 `feat/restaurant-dashboard-default-allgold` 部署的 RBAC 角色转发修复, 被另一个并发 session 从它的分支部署 Java 时**覆盖** → prod jar 丢了我的修复 → 餐厅驾驶舱总营收回归 ¥0(订单数/门店数正常, 唯独金额被 RBAC 剥零, 因为 GoldFinanceClient 不再转发角色)。重新部署才恢复, 但只要再有 session 部署 feature 分支就会再回归。

**根治**: prod = main。所有 session 的工作都 merge 进 main, 都从 main 部署 prod, 就不会互相覆盖(main 累积所有人的工作, 不丢)。

---

## 验证边缘情况 (feature 在 prod 验证)

有时 test 环境缺数据(e.g. 餐厅真实数据只在 smartbi_prod_db), 必须在 prod 验证 feature。此时:

1. 优先 test 验证(能验就别碰 prod)。
2. 若**必须** prod 验证 feature 分支: 验完**立即 merge 到 main + 从 main 重新部署 prod**, 让修复 durable(否则下一个并发部署就覆盖)。
3. 部署后**核对运行中的 jar/代码确含你的修复**(per `feedback_concurrent_deploy_r2_path_collision`):
   ```bash
   # Java: 确认活跃 jar 含你的修复标记
   ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar 'BOOT-INF/classes/.../YourClass.class' | strings | grep -c '<你的修复标记>'"
   ```

---

## 速查

| 场景 | 做法 |
|---|---|
| 开始任何代码任务 | `git worktree add -b feat/X ../cretas-X origin/main` (off origin/main) |
| 任务完成 | PR → merge main; 先 `git diff origin/main...HEAD --stat` 确认 scope 干净 |
| 部署 prod | 先 `git checkout main && git pull`, 再 deploy(绝不从 feature 分支) |
| 必须 prod 验 feature | 验完立即 merge main + 从 main 重部 + 核对运行 jar 含修复 |
| 清理 | `git worktree remove ../cretas-X`(⛔ 不要 mklink /J 共享 node_modules, 见 concurrent-edit-safety Rule 7) |

关联: `concurrent-edit-safety.md`(commit 范围保护)、`feedback_concurrent_deploy_r2_path_collision`(memory)、`server-operations` skill(部署脚本)。
