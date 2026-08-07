# Worktree 隔离 + 只从 main 部署 prod (HARD RULE)

**最后更新**: 2026-08-07
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

⚠️ **非 docs 批次必须带 `--task-id <任务ID>`**: ACTIVE 台账常驻 30+ 条他人在飞任务, 不传 `--task-id` 时门禁要求「全局零未完成任务」, 这个条件实际上永远不成立 → 任何非 docs 直推都会被拒。`--task-id` 把门禁收窄成「**你自己这条**已归档」, 与门禁本意一致(协调者在同一 commit 归档自己的批次), 不会放松对自己的要求 — 自己那条还挂着 `in-progress`/`review` 照样拦。
| 碰 backend / web-admin 代码 | **PR** — `Python Gate` / `Web Admin Gate` / `Secret regression gate` 按 paths 在 PR 上跑; PR 号是台账/memory 的引用锚点 |
| AGENTS.md / workflows / `scripts/deploy/*` / db 迁移 / entity / repository / security | **强制 PR** — fastlane 将这些列为高风险路径自动拒绝(除非 owner 显式 `YES-HIGH-RISK-REVIEWED` 覆盖, 默认不用) |

⚠️ **2026-08-07 订正**: 本表原写「CI `JPA repository query startup gate` 挂在 PR 上」——
**已过期**。`e6d1fffe75 refactor(ci): JPA 检查改为合并后告警而非 PR 门禁` 删掉了
`jpa-gate-pr.yml`, 现在只剩 `jpa-gate-main.yml`(`push: branches: [main]`)。
走 PR 的理由换成上表里那三道按 paths 触发的闸 + PR 号作为台账锚点。
📌 判「某个闸还在不在」看 **yml 文件**, 不要看 `gh workflow list` ——
那个列表至今仍列着已删的 `JPA repository query gate (PR)`。

⚠️ **看 CI 结论的两个坑**(同日实测踩过):
1. `gh` 的时间戳是 **UTC**, 本机 CST(+8) —— 直接相减会得出「CI 停摆 N 小时」的
   假结论。先 `date -u` 再比。
2. PR 上的 `failure` **先查是不是 `cancelled`**: 这些工作流带
   `concurrency: cancel-in-progress`, push 与 pull_request 在同一 ref 各触发一次
   会互相取消, 而 `gh pr checks` 把取消显示成失败。
   用 `gh run view <id> --json jobs --jq '.jobs[] | .name+" -> "+(.conclusion//.status)'`。

⛔ 双轨底线不变: **任何通道都必须推上 origin/main 后才可部署**。origin/main 是多 session 唯一汇合点(本规则 5/30 事故的根治点), "本地 main 不推就部署"在任何轨道都禁止 — 省的是 PR 仪式往返, 不是 push 本身。

### 3. prod 永远从 main 构建/部署 —— 绝不从 feature 分支部署 prod

```bash
# ✅ 正确: 部署 prod 前先在 main
git checkout main && git pull origin main

# 正常 Java/Web 发布 — 统一入口 (脚本自身强制 clean HEAD == origin/main, 与本规则互为保险):
./scripts/deploy/prewarm-main-artifact.sh --tests '<tests>' --wait 420   # 合并后先预热, 不能省
./scripts/deploy/release-cretas.sh --phase deploy --base-sha '<Base SHA>' --tests '<tests>' --confirm-prod YES-PROD
# 判据: DEPLOY_EXIT=0 且日志里 RELEASE_FINAL_STATUS 恰好 1 次 (不出现本身就是失败信号)

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

## 🔌 GitHub 不可用时(账号停用 / 平台故障): 本地 main 是唯一汇合点

**2026-08-07 实证**: GitHub 账号被停用, push/fetch/gh 全线 403, `origin/main` 冻结在
`b1a9c2b465`。当天有 2 条并行 session 各自攒了改动。

⛔ **此时最危险的做法是「各自从自己的分支部署」** —— 那正是 5-30 事故的形态(部署上传到
**固定共享路径**, last-write-wins), 而且没有 GitHub 就连事后对账都没有。铁律 3 的**意图**
(prod = 一个所有人都汇合过的点)在离线期间照样成立, 只是载体从 `origin/main` 换成**本地 `main`**。

### 为什么本地 merge 根本不需要网络

**同一个仓的所有 worktree 共享同一个 `.git` 对象库和 refs**。别的 session 建的分支立刻可见、
可 merge、可 diff:

```bash
git log origin/main..codex/other-session-branch   # 从任何 worktree 都能看
git merge --no-ff codex/other-session-branch      # 直接合, 零网络
```

`origin/main` 只是个**本地缓存的 ref**, GitHub 断了它不会消失, 只是不再前进。

### 协议

1. **汇合点固定用本地 `main`** —— 不要新造一个整合分支。多一个汇合点 = 问题翻倍。
2. **只有占着 `main` 的那个 worktree 能往 main 上合**(git 不允许两个 worktree 检出同一分支)。
   这是好事: 天然把合并串行化了。
   ⚠️ **动它之前必须先 `git status --porcelain` 确认那个 worktree 干净** —— 它是别人的活工作区。
3. **部署从 detached worktree**, 绕开「分支被占用」:
   ```bash
   git worktree add --detach ../cretas-deploy-offline $(git rev-parse main)
   cd ../cretas-deploy-offline
   SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-web-admin.sh --env prod --confirm-prod YES-PROD
   SKIP_GIT_CHECK=1 ./scripts/deploy/deploy-backend.sh  --env prod
   ```
4. **合并后、部署前**必须跑一次迁移撞号闸 —— **git 对 Flyway 撞号一个字都不报**(文件名不同),
   要到启动才炸:
   ```bash
   mvn -o test -Dtest='FlywayVersionUniquenessTest,*RepositoryQueryValidationTest'
   ```

### 🔴 「部署入口全被闸挡住了」是个不完整的结论

2026-08-07 有 session 判定「四个部署入口全都有 exact-main 闸 —— 连『换个入口』这个选项都
不存在」, 于是**停下不部署了**。那是错的:

- `check_git_sync` 的 `git fetch origin main` 失败**只 WARN**(`|| log WARN "(offline?)"`),
  然后拿**本地缓存的** `origin/main` ref 比对 —— 离线完全跑得动
- 脚本自己在报错里写着逃生门: `SKIP_GIT_CHECK=1`
- 它会把 `HEAD != origin/main` 的完整警告打出来再继续, 有痕迹可查

⚠️ 逃生门只在**「已合进本地 main + 从 main 部署」**这个前提下用。拿它从自己的 feature 分支
直接部署 prod, 就是把 5-30 事故重演一遍。

### GitHub 恢复后的收尾(必须做, 否则漂移永久化)

```bash
# ✅ 各条 feature 分支分别 push → 开 PR → 走 CI → 正常合进 origin/main
git push origin codex/claude-xxx && gh pr create ...
# 全部合完之后再
git checkout main && git reset --hard origin/main
```

⛔ **不要 `git push origin main`** —— 那会把十几个 commit 绕过 CI 和 review 一次性推上去,
而且离线期间攒的通常含 backend 代码(按「合入通道双轨」必须走 PR)。

### 离线期间的账要写下来

prod 上跑的东西在 GitHub 上查不到, 所以**每次离线部署都要记**: 部署了哪个本地 commit、
含哪些分支、DB 侧跑了哪些迁移。写进当天的 handoff, 恢复后逐条核销。

---

## 速查

| 场景 | 做法 |
|---|---|
| 开始任何代码任务 | `git worktree add -b feat/X ../cretas-X origin/main` (off origin/main) |
| 任务完成 | PR → merge main; 先 `git diff origin/main...HEAD --stat` 确认 scope 干净 |
| 部署 prod | 先 `git checkout main && git pull`, 再 deploy(绝不从 feature 分支) |
| 必须 prod 验 feature | 验完立即 merge main + 从 main 重部 + 核对运行 jar 含修复 |
| 清理 | `git worktree remove ../cretas-X`(⛔ 不要 mklink /J 共享 node_modules, 见 concurrent-edit-safety Rule 7) |
| **GitHub 不可用** | **合进本地 `main`(占着它的那个 worktree 里合, 先查它干不干净) → `git worktree add --detach $(git rev-parse main)` → `SKIP_GIT_CHECK=1` 部署 → 恢复后各 feature 分支走 PR, 本地 main `reset --hard origin/main`** |

关联: `concurrent-edit-safety.md`(commit 范围保护)、`feedback_concurrent_deploy_r2_path_collision`(memory)、`server-operations` skill(部署脚本)。
