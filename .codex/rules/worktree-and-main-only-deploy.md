# Worktree 隔离 + 只从 main 部署 prod (HARD RULE)

**最后更新**: 2026-07-18
**触发事故**: 2026-05-30 青花椒 RBAC 修复在 prod 被并发 session 的 Java 部署覆盖, 总营收回归 ¥0。

---

## ⛔ 三条铁律 (任何代码工作都遵守)

### 1. 永远不在主工作目录直接干活

**禁止**在 `C:\Users\Steve\my-prototype-logistics`(主工作目录)直接改代码 / 跑构建 / 长任务。

每个任务开**独立 git worktree**:

```bash
git worktree add -b codex/<task> ../cretas-<task> origin/main   # 永远 off origin/main
cd ../cretas-<task>
# 全部工作在这里做
```

**Why**: 主目录被多个并发 session(Claude chat / Cursor / VSCode)共享, 互相污染 —— working-tree 改动串台、commit 范围被并发文件污染、rebase/checkout 被别 session 的未提交改动阻塞。worktree 物理隔离。

### 2. 完成 → 受审控地进入 main（不在 feature 分支上累积部署）

默认是任务完成 → PR → merge 到 `main`。**worktree 永远 off `origin/main`** 开（不要 off 别的 feature 分支）。

用户明确要求“不做 PR/直接发 main”时，允许单协调者使用受控快速通道：

```bash
./scripts/deploy/publish-main-fastlane.sh \
  --base-sha <ACTIVE 登记的 origin/main SHA> \
  --confirm YES-DIRECT-MAIN
```

必须同时满足：clean `codex/*` worktree、HEAD 线性后继于登记 base、最终 commit 已归档 ACTIVE 并释放全部 scope、目标验证通过、推送前 fetch 后 `origin/main` 仍等于 base。脚本只做非 force fast-forward push；任一失败必须回到一次 PR，不得手工强推。高风险文件默认仍要 PR；用户对本次高风险直发明确授权且所需门禁已通过时，才可增加 `--allow-high-risk YES-HIGH-RISK-REVIEWED`。

merge/直发前确认 scope 干净:
```bash
git diff origin/main...HEAD --stat   # 应只有你的文件, 没有 sister 的 audit/docs
```
如夹带 sister 文件 → 说明 worktree 没 off origin/main, 用 `git cherry-pick <你的commit范围>` 到干净的 origin/main worktree 重做。

### 3. prod 永远从 main 构建/部署 —— 绝不从 feature 分支部署 prod

```bash
# ✅ 正确: 部署 prod 前先在 main
git checkout main && git pull origin main
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
| 开始任何代码任务 | `git worktree add -b codex/X ../cretas-X origin/main` (off origin/main) |
| 任务完成（默认） | PR → merge main；先 `git diff origin/main...HEAD --stat` 确认 scope 干净 |
| 用户明确不做 PR | 归档 ACTIVE 后运行 `publish-main-fastlane.sh`；门禁失败则回到一次 PR |
| 部署 prod | 先 `git checkout main && git pull`, 再 deploy(绝不从 feature 分支) |
| 必须 prod 验 feature | 验完立即 merge main + 从 main 重部 + 核对运行 jar 含修复 |
| 清理 | `git worktree remove ../cretas-X`(⛔ 不要 mklink /J 共享 node_modules, 见 concurrent-edit-safety Rule 7) |

关联: `concurrent-edit-safety.md`(commit 范围保护)、`feedback_concurrent_deploy_r2_path_collision`(memory)、`server-operations.md`(部署脚本)。
