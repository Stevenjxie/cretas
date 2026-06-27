# 并发编辑安全规范

**最后更新**: 2026-04-11

---

## 背景

多个 Claude Code session / Cursor / VSCode 同时打开同一文件时, 可能互相覆盖编辑. 更隐蔽的, **commit 阶段也可能被并发 session 的文件串到一起** — husky/lint-staged 会 auto-stage.

**事故 1 (2026-04-08)**: 重做 `scripts/deploy/deploy-backend.sh` v5.0 时, 写了 ~150 行 edits 后, 文件被并发 session 覆盖, 只保留最后的 29 行. Deploy run 一度成功执行 BG 逻辑 (说明文件之前是正确的), 但 git diff 只剩 29 行. 被迫重做所有 edits, 之后 commit `09af47780` 保住工作.

**事故 2 (2026-04-11)**: Round 5 follow-up commit `19d8d41ab` 本意只提交 2 个 deploy 脚本改动, 结果 pre-commit hook 带入了另一个并行 session 写的 5 个无关文件 (workflow YAML / Java Tool / 规划 MD / audit CSV/MD). commit message 只描述 deploy 修复, 那 5 个文件成了无描述的 scope creep, 已推送 origin/main. **教训: 并发环境下 `git add <specific>` 不足以锁定 commit 范围, 必须 commit 前再 `git status`.**

---

## 规则

### 1. 里程碑式 commit（最重要）

任何连续 3+ Edit 的同一文件, 或 15-30 分钟工作量, **立即 commit** (可用 `WIP:` 前缀):

```bash
git add <file> && git commit -m "WIP: <phase>"
```

完成后 `git rebase -i` squash 即可. 不要等 "全部做完再 commit" — 这是高风险模式.

### 2. 长任务用 git worktree 隔离

1 小时+ 的大工程 (脚本重构、跨文件 refactor) 开独立 worktree, 物理隔离:

```bash
git worktree add ../<project>-<task> HEAD
cd ../<project>-<task>
# 工作...
# 完成后
git worktree remove ../<project>-<task>
```

绝对隔离, 并发 session 不可能互相覆盖.

### 3. Edit 后验证预期 diff 大小

关键 edit 之后立即对比:

```bash
git diff --stat <file>
grep -c "<关键字>" <file>  # 预期值 vs 实际值
```

如果 diff 远小于预期 (e.g. 你改了 150 行但只显示 29 行) → 文件被覆盖, 用 `git log -p <file>` 或 `git reflog` 找回.

### 4. 修改共享脚本/规则前 git status

修改 `scripts/deploy/*.sh`、`.codex/rules/*`、`.agents/skills/*`、`pom.xml`、`package.json` 等共享文件前:

```bash
git status --short <file>
```

如显示 unstaged 变化而你不记得是自己做的 → **停手先确认**, 不要直接 edit.

### 5. Commit 前也要 `git status` (Apr 11 事故新加)

**即使你只 `git add` 了特定文件, commit 前仍然必须 `git status` 确认 staging 区**:

```bash
git add scripts/foo.sh scripts/bar.sh
git status --short                    # ← 这一步不能省
# 只有你预期的文件在 staged 区, 才 commit
git commit -m "..."
```

**Why**: `husky` / `lint-staged` / pre-commit 钩子会在 `git commit` 触发时 auto-stage 新文件或格式化修改. 并发 session 同时写别的文件时, 你的 commit 会意外"顺走"它们. 单纯 `git add <specific>` **不足以**锁定 commit 范围.

**如果 commit 后发现 scope creep**:
- **已推送** → 无法干净回滚 (不违反 no-destructive-git 规则), 只能写 follow-up commit 补 doc
- **未推送** → `git reset --soft HEAD~1` 退回 staging, 分两个 commit 重写 (soft reset 不丢工作)

### 5b. 并发-安全 commit 命令 (Apr 28 2026 incident-driven fix)

Apr 28 2026 一晚连续踩 3 次同样事故: `git add F1 F2 && git commit -m "msg"` 把并发 session 已 staged 的不相关文件吞进我的 commit. 修法:

```bash
# ❌ 不安全: 把所有 staged 文件包括并发 session 的全 commit
git add backend/foo.py backend/bar.py
git commit -m "feat: my change"

# ✅ 安全: --only mode (default when paths given) — 仅 commit 这 2 个文件
#         即使别 session staged 了 X / Y, 它们 stay staged 不进我的 commit
git commit -m "feat: my change" -- backend/foo.py backend/bar.py

# ✅ 更安全: 用 wrapper 脚本, 自动 verify-after-commit
./scripts/safe-commit.sh "feat: my change" backend/foo.py backend/bar.py
```

**Git 行为参考**: per `git-commit(1)`, "When PATHS are given, the command makes a commit that only includes the changes made to the named paths." `--only` 是 paths 提供时的 default, index 中其他 staged 文件不受影响.

**适用场景**: 任何时候有 2+ chat / IDE 同时活跃. **应该是默认 commit 习惯**, 不仅"怀疑并发"时才用.

**`safe-commit.sh` 额外好处**:
- pre-commit verify: 列出其他 staged/dirty 文件 (不会进 commit) 让你看清现状
- post-commit verify: `git show --name-only HEAD` 对比预期 vs 实际, 抓 husky/lint-staged 偷加的

### 6. 避免同时打开同一文件

- **Cursor/VSCode auto-save** 会覆盖外部修改. Claude 正在改的文件不要在 IDE 里打开 (哪怕只是查看).
- **多个 Claude Code chat** 同时 edit 一个文件会互相覆盖 — 不要并行 edit 同一文件.
- **必须并行时**: 用 git worktree 隔离, 或者明确分工 (session A 改 fileA, session B 改 fileB).

### 7. ⛔ Subagent worktree 别用 `mklink /J` 共享 node_modules (Windows 致命)

**事故 (2026-05-18)**: 给并行 subagent 写 brief 时,为了省 `npm install` 时间,告诉它们在 worktree 里 junction 共享主 repo 的 `node_modules`:
```bash
cd web-admin
cmd //c "mklink /J node_modules C:\Users\Steve\my-prototype-logistics\web-admin\node_modules"
```
Subagent 完成, `git worktree remove --force <worktree-path>` 清理. **Junction 删除时, Windows 把 target dir 内容也递归清空了** — 主 repo 的 `web-admin/node_modules` 被掏空,下次 deploy 跑 `vite build` 报 "vite 不是内部或外部命令" 失败. 必须 `npm install --legacy-peer-deps` 重装 (~20s + 阻断 prod deploy).

**Why**: Windows `mklink /J` 是 NTFS directory junction (reparse point). 不同 explorer / del / rmdir 对 junction 的处理不一致 — 某些路径 (尤其是 `git worktree remove --force` 触发的递归删除) 会把它当成"真目录"递归删, 而不是"删 reparse point 本身".

**Symptom check**: `ls -la web-admin/node_modules` 应该有几百个子目录 (vue, vite, element-plus, ...). 如果只看到 `./` 和 `../` (空), 就是被掏空了.

**正 pattern (subagent brief 里写)**:
```bash
# 安全: 让 subagent 在 worktree 里独立装 npm 包. ~20s + 不共享, 不会被 junction 删除连坐.
cd web-admin && npm install --prefer-offline --legacy-peer-deps
```

`--prefer-offline` 让 npm 优先用本地 cache (主 repo 装过的包会有 cache hit), 实际下载量很小 → 接近 junction 速度,无连坐风险.

**Mvn/Python 不受影响**: maven `.m2` cache 在 `~/.m2`,跨 worktree 天然共享. Python `venv` 不共享但每个 worktree 独立装也快.

**适用范围**:
- 写 Agent tool brief 时,**禁止**告诉 subagent 用 `mklink /J` 共享 `node_modules`.
- 也别用于其它 build-tool dir (e.g. `target/`, `.next/`, `.gradle/`).
- 单文件 hardlink (`mklink /H`) 可以 — 没有递归删除问题. 但通常用不到.

---

## 优先级组合

| 场景 | 方案 |
|---|---|
| 单 session 内改 3+ 文件 | 里程碑 commit (规则 1) |
| 2+ chat 都要改同一文件 | git worktree 隔离 (规则 2) |
| 不确定是否并发 | git status 防御 (规则 4) + 关闭其他 editor (规则 6) |
| **Commit 阶段保护 scope** | **`git commit -- F1 F2` 或 `safe-commit.sh`** (规则 5b) — 即使 staged 区被并发 session 污染, 仅 commit 列出的文件 |
| **Subagent worktree node_modules** | **`npm install --prefer-offline --legacy-peer-deps`** (规则 7) — 禁止 `mklink /J`, Windows worktree 清理会把主 repo 的 node_modules 一起掏空 |
| 长期约束 | 这个 rule 本身 + memory `feedback_concurrent_edit_safety.md` |

**Apr 8 事故正确做法**: Phase C 产品化应该用 **规则 1 + 规则 2** — 开 worktree 跑完整流程, 每个 phase 完成立即 commit.

**Apr 11 事故正确做法**: `git add scripts/deploy/deploy-backend.sh scripts/lib/deploy-common.sh` 之后, 在 `git commit` 前先 `git status --short` 看一眼, 发现有 `.github/workflows/*.yml` / `docs/plans/*.md` 出现在 staged 区就会立刻警觉 — 那些是另一 session 的文件, 应该 `git restore --staged <file>` 把它们从我的 commit 里剔除.
