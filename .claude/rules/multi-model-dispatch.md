# 多模型分发路由规范 (Multi-Model Dispatch)

**最后更新**: 2026-06-04
**触发**: Steve 要"Claude 出计划时直接产出可分发的 task 输出, 我去派给其他 chat, 并指定哪些给 GPT-5.5·Codex / 哪些给 Composer 2.5"。
**关系**: 这是 [`parallel-work-analysis.md`](./parallel-work-analysis.md) 的**升级层** —— 老规则回答"能不能并行", 本规则在它之上回答"每块并行工作派给哪个模型 + 怎么物理隔离 + 怎么交接回 main"。隔离铁律继承 [`worktree-and-main-only-deploy.md`](./worktree-and-main-only-deploy.md) + [`concurrent-edit-safety.md`](./concurrent-edit-safety.md)。

---

## 核心理念

```text
Opus 4.8       = 总工 / 架构师 / 高风险决策 / 上线前终审 (贵但稳, 负责判断对不对)
Composer 2.5   = 日常执行工程师 (便宜耐用, 负责干活)
GPT-5.5·Codex  = 复杂执行 + CLI/E2E/构建 + 第二审查 (强执行, 复杂工程操作)
```

三条铁律:

1. **路由按任务性质, 不按固定比例。** 没有"70/20/10"这种配额 —— 一个清楚的改样式任务永远走 Composer, 一个权限改动永远 Opus 把关, 跟当天用了多少额度无关。
2. **隔离是硬约束, 不是温柔提醒。** 多个模型/chat 同时干活 = 必然撞文件 / 撞 commit / 撞 prod jar。每个分发任务 **必须**独立 worktree off `origin/main`。见下方"隔离铁律"。
3. **高风险动作不许执行者自己收尾。** prod 部署 / migration / 权限 / 架构 这四类 (见⛔红线) 一律回 main 由 Opus 终审, 不交给 Composer/Codex 自审自部署。

> **Why 隔离是第一性的**: Steve 已经为多 session 并发流过血 —— 5/30 青花椒 RBAC 修复在 prod 被并发 session 的部署**覆盖**, 总营收回归 ¥0; 4/8 deploy 脚本被并发 session 覆盖只剩 29 行; 4/11+4/28 commit scope 被并发 staged 文件污染。对 Steve 来说多模型路由的核心命题不是"谁干什么", 而是"隔离怎么被强制"。

---

## 默认行为: 每个计划末尾产「分发卡」

**触发**: 我(Claude/Opus)每次出**计划 / 设计**, 末尾**默认**追加一段分发卡。这取代 `parallel-work-analysis.md` 的"并行工作建议"输出格式(把它升级成带模型路由 + 隔离命令的版本)。

**形态**: 总览表 + 每任务独立 brief 卡。

### 1) 分发总览表

```markdown
## 🚦 分发总览
| # | 任务 | 推荐模型 | 可否并行 | worktree 分支 | 🔒红线 |
|---|------|---------|---------|--------------|--------|
| 1 | KPI 看板前端 | Composer 2.5 | ✅ | feat/524-ui | |
| 2 | 后端口径 + migration | GPT-5.5·Codex | ❌(依赖1) | feat/524-api | 🔒 |
| 3 | 架构设计 + 上线终审 | Opus(本chat自留) | - | main | 🔒 |
```

### 2) 每任务 brief 卡 (即贴即用, 复制就能丢进对应 chat)

```markdown
## 卡N → 贴给 {Composer 2.5 | GPT-5.5·Codex | Opus自留}
**目标**: 一句话说清做什么 + 推荐这个模型的理由
**worktree**: git worktree add -b feat/<task> ../cretas-<task> origin/main   # 永远 off origin/main
**允许改**: <文件/目录范围, 越窄越好>
**禁改**: <锁死区, 防 scope 污染>
**验收**: <测试命令> 通过 + <证据: 截图/日志/headed E2E>
**并行**: ✅ 与卡X独立 / ❌ 依赖卡Y(说明冲突文件)
**交接**: 完成 → PR off origin/main → `git diff origin/main...HEAD --stat` 确认 scope 干净
[🔒红线项追加] **⛔ 收尾约束**: 只做到"实现+自测+PR", 不许自部署 prod / 不许自 merge → 回 main 由 Opus 终审 + 部署
```

brief 卡要**自包含** —— 别的 chat 看不到本 chat 的上下文, 卡里必须带全它开工所需的一切(目标/范围/禁区/验收/隔离命令), 不能假设它知道"我们刚才聊的"。

---

## 路由启发式 (任务性质 → 模型)

| 任务性质 | 推荐 | 理由 |
|---|---|---|
| 改页面/组件/样式 (Vue/RN) · 普通 bug · 补 lint/类型/测试 · 照任务文档批量小改 | **Composer 2.5** | 边界清楚, Cursor 内最顺手最便宜, 高频执行任务 |
| 跨模块 bug · 查日志 · headed Playwright/E2E · 构建排查 · 仓库级检查 | **GPT-5.5·Codex** | CLI agent 更适合命令行/测试/构建型工作 |
| 构建/CI/部署脚本问题 | **Codex 执行 + Opus 审** | 执行与风险判断分开 |
| **Java AI Tool-Skill 意图路由 · Python↔Java parity port** | **Opus**, 或 Opus 写死严格 brief 后给 Codex | 有 `.claude/rules/ai-intent-tool-skill-architecture.md` / `python-java-port.md` 硬规则(Decimal/Map.of order/Lombok null/HALF_UP 等 12 条), 易踩, 执行者无规则上下文会翻车 |
| 🔒 Flyway/migration/schema · 权限/RLS/多租户/业态隔离 · 架构/跨模块重构/新实体 · 上线前 diff 终审 | **Opus 把关** | 见下方⛔红线 |
| 同一问题某模型修 2 轮还没好 | 切 **Opus** 做 root-cause review | 不让一个模型一直撞墙; 别盲改 |
| 某模型改乱了 / 你不放心 | **Opus** root-cause review (先停下判断, 不继续盲改) | |

> 模型名以 Steve 当前工具箱为准(Cursor Composer / OpenAI Codex / 本 Claude chat)。名字变了路由逻辑不变 —— 关键是"执行 vs 判断"分层。

---

## ⛔ Opus 红线 (执行者不许独立收尾部署 prod)

以下四类任务的 brief 卡**必须标 🔒**, 执行者(Composer/Codex)**只做到"实现 + 自测 + PR off origin/main"**, prod 收尾一律**回 main 由 Opus 终审 + 部署**:

| 红线类别 | 为什么 (事故证据) |
|---|---|
| **prod 部署 / DB migration / Flyway schema 变更** | `deploy-backend.sh` 上传到**固定共享 jar 路径**, 多 session 从各自 feature 分支部署 prod = last-write-wins 互相覆盖(5/30 RBAC 被覆盖 ¥0)。Flyway 跨 session 撞号(merge 后 origin/main 出现两个同号 V* → 启动报"more than one migration"阻断所有人)。 |
| **权限 / RLS / 多租户 / 业态(餐饮↔工厂)隔离** | 这类 bug 造成数据泄漏 / 越权 / 业态串台, Steve 多次踩(餐饮路由撞制造业工具瞎编、营收脱敏漏配)。必须 Opus 终审。 |
| **架构设计 / 跨模块重构 / 新实体 / 新服务** | 需要判断力不只是写代码; Opus 主导设计, Composer/Codex 只执行已定方案。 |
| **上线前 code review 终审** | 任何 merge/deploy 前的最终 diff 审查由 Opus 做(可配合对抗性多-agent 终审), 不交给执行者自审自过。 |

红线不是"不能派活", 而是"派活可以、自部署不行"。执行者把活干到 PR, Opus 接手终审 + 从 main 部署。

---

## 隔离铁律 (继承现有规则, 分发卡必须体现)

- **每任务独立 worktree off `origin/main`**: `git worktree add -b feat/X ../cretas-X origin/main`。绝不在主工作目录直接干。绝不 off 别的 feature 分支(会夹带 sister commit 污染 PR scope)。
- **prod 永远从 main 部署**, 绝不从 feature 分支部署 prod。
- **commit 锁 scope**: `git commit -m "..." -- F1 F2`(`--only` 模式) 或 `./scripts/safe-commit.sh`, 防 husky/lint-staged 把并发 session 的 staged 文件吞进 commit。
- **⛔ 不准 `mklink /J` 共享 node_modules**: Windows `git worktree remove` 会把主 repo 的 node_modules 一起掏空。subagent/worktree 各自 `npm install --prefer-offline --legacy-peer-deps`。

---

## 交接协议 (闭环)

```text
Opus 出计划 + 分发卡
   ↓ (你复制 brief 卡分派)
Composer / Codex 各自在隔离 worktree 实现 + 自测
   ↓
PR off origin/main → git diff origin/main...HEAD --stat 确认 scope 干净(无 sister 文件夹带)
   ↓
Opus 终审 diff (红线项必经此关) → merge 进 main
   ↓
Opus 从 main 部署 prod → 核对运行中 jar/代码确含修复
```

并行任务交接靠 **brief 卡 + git diff + 测试结果**, 不靠口头上下文。

---

## 速查判断树

```text
任务很清楚, 只是改代码/样式/补测试?
  → Composer 2.5

任务清楚, 但涉及 CLI / 测试 / 构建 / headed E2E / 跨模块查日志?
  → GPT-5.5·Codex

涉及 Java Tool-Skill 意图路由 / Python↔Java parity (有硬规则)?
  → Opus, 或 Opus 写死严格 brief 后给 Codex

🔒 涉及 prod 部署 / migration / Flyway / 权限 / RLS / 业态 / 架构 / 上线终审?
  → Opus 把关 (执行可派, 收尾回 main 终审)

某模型修 2 轮没好 / 改乱了?
  → 切 Opus 做 root-cause, 别盲改
```

---

## 反 pattern (绝对禁止)

- ❌ 三个模型同时改同一文件 → 互相覆盖(见 `concurrent-edit-safety.md`)
- ❌ 执行者从 feature 分支直接部署 prod → 覆盖别人的 prod jar(5/30 事故)
- ❌ brief 卡假设别的 chat 知道"我们刚才聊的" → 卡必须自包含
- ❌ 红线任务交给 Composer/Codex 自审自部署 → 数据泄漏/覆盖风险
- ❌ 按固定额度比例硬塞 Opus 干低价值执行 / 硬塞 Composer 做高风险决策
