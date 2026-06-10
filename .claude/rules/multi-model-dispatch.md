# 多模型分发路由规范 (Multi-Model Dispatch)

**最后更新**: 2026-06-10
**触发**: Steve 要"Claude 出计划时直接产出可分发的 task 输出, 我去派给其他 chat, 并指定哪些给 GPT-5.5·Codex / 哪些给 Composer 2.5"。增补：Sonnet 执行层 + 三轴路由（模型/effort/orchestration）+ 预算均衡注记 + 两通道 + 审查分层。**2026-06-10**: Fable 5 上线(2x Opus 消耗)→ 加 model 轴破玻璃顶层 + Fable 5 定位铁律(organizer 本体不换 Fable 5, 只派 `fable` subagent 做四落点单点)。
**关系**: 这是 [`parallel-work-analysis.md`](./parallel-work-analysis.md) 的**升级层** —— 老规则回答"能不能并行", 本规则在它之上回答"每块并行工作派给哪个模型 + 怎么物理隔离 + 怎么交接回 main"。隔离铁律继承 [`worktree-and-main-only-deploy.md`](./worktree-and-main-only-deploy.md) + [`concurrent-edit-safety.md`](./concurrent-edit-safety.md)。编排顶层入口 → [`organizer-protocol.md`](./organizer-protocol.md)。

---

## 核心理念

```text
Fable 5        = 破玻璃判断顶层 (2x Opus 消耗, 比 Opus 还稀缺; earned-not-predicted: 只在 Opus 已实际试过且卡住后升, session 个位数次)
Opus 4.8       = 总工 / 架构师 / 高风险决策 / 上线前终审 (贵但稳, 负责判断对不对) — organizer 本体常驻这档
Sonnet         = Claude 侧默认执行层 (便宜 20x, rule-heavy in-harness, 自动加载 .claude/rules)
GPT-5.5·Codex  = 复杂执行 + CLI/E2E/构建 + 第二审查 (强执行, 复杂工程操作)
Composer 2.5   = 独立 UI / 样式 / lint / 补测试 (Cursor 内便宜耐用)
```

**预算均衡注记**: Claude Max 20x + GPT 10x(较小桶) + Cursor 三个都是 flat → **铺开用三个、别撑爆更小的 GPT 10x**。Claude 侧执行优先走 Sonnet 拉长 20x（省 Opus 周额度），Opus 集中用在需求框架/🔒终审/难架构。

### ⛔ Fable 5 定位铁律 (2026-06-10 增补: Fable 5 上线)

**Fable 5 是比 Opus 还稀缺的资源, 不是"更好的 Opus organizer"。** Fable 5 = **2x Opus 消耗** → 烧周限额速度是 Opus 的两倍; Opus 本就"纯 Opus 一周绝对不够"。所以保护 Opus 的纪律(`分配者不是满载消费者`)对 Fable 5 **加倍执行**。

| 必须 | 禁止 |
|---|---|
| **organizer 本体保持 Opus 4.8 + high** — Fable 5 由 organizer 通过 `fable` subagent (Agent tool model override 支持) 在**单点**派出, 自己 body 不动 | ❌ 把整个 organizer 换成 Fable 5 → 每轮廉价路由分诊 ×2 = `满载消费者` 反模式 ×2, 几天炸周限额 |
| **earned-not-predicted**: Fable 5 只在 Opus **已经实际试过且明确卡住**之后升, 不靠"我觉得这超出 Opus 能力"的预测(那是不可证伪的, organizer 有升级偏好会滥用) | ❌ 预先"为了保险用最好的模型"。没观察到 Opus wobble 就不许升 |
| **model 轴新顶**: 难判断升级阶梯 = Sonnet 4.6 → Opus 4.8 → **Fable 5**(effort 在每档内作次级旋钮)。Opus 4.8 × xhigh 已试且 wobble → 升 Fable 5, 而不是停在 Opus 继续烧 effort | ❌ 任何执行(Sonnet/Codex/Composer)/路由分诊/批量机械/fan-out workers。`cost = tokens × 2x`, 体量工作进 Fable 5 是灾难 |

#### 防滥用闸 + 防荒废闸 (闸要自我执行, 不靠 organizer 自律)

**四落点, 按性价比排序(不是"终审最强"——见下纠错):**

| 落点 | 触发(客观优先) | 注意 |
|---|---|---|
| ① **卡死调试升级**(最干净) | Opus 已修 2 轮没好 → **客观触发, 应当升**(别让 Opus 第三次硬撞) | 你本就想要不同模型新视角, subagent rediscovery 在这里是 feature |
| ② **真有判断模糊的难架构选型** | Opus **xhigh 已试且两版结论打架/拿不准** 才升 | 没试过 Opus 不许直接 Fable 5 |
| ③ **模糊高风险需求框架** | 同②: Opus 先框, 框不清且 stakes 高才升 | ⚠️ 框架常 token 量大, 2x 贵; Opus 够就别升 |
| ④ **🔒 终审里"不可逆/高爆炸半径"窄子集** | prod 迁移 / RBAC·RLS·多租户数据泄露 / 资金路径, **且 diff 小** | ⚠️ **不是每个终审**。大 diff 终审用 Opus + 对抗 fan-out 更划算(见纠错) |

**⛔ 纠错(第一稿的错)**: "🔒 终审 = 最强候选" **站不住**。organizer 本体(Opus)做终审时已持有 diff context; 交给全新 `fable` subagent = **2x 费率 + 从头 rediscovery context** 双重惩罚。终审是 Fable 5 性价比**最差**的落点之一(尤其大 diff)。真正最强是 ① 卡死升级(客观触发 + 想要异模型视角)。

**频次闸(对标 max effort 的"几乎不用")**: Fable 5 应是 **session 内个位数次**的破玻璃动作, 不是每个 risky 任务都点。**想点第 2 次就停下自检**: 是不是在用 model 轴掩盖 brief 没写清 / 需求没框清 —— 那应该回去修 brief, 不是升模型。

**防荒废(别变死信)**: 上面 ① 是 **affirmative "应当升"** —— Opus 修 2 轮没好时**别为省额度硬留 Opus**, 那是 Fable 5 存在的意义。worked examples:
- ✅ **会点**: 5/30 RBAC 角色转发那种"改对一处 / prod 营收归零"的 permission 判断, Opus xhigh 给了两版互相矛盾的结论 → 派 1 个 `fable` subagent 单点定夺。
- ❌ **不点**: 例行 risky review(逻辑直白的带迁移 PR)、token 量大的需求框架、任何执行/批量 —— 全留 Opus/fleet。

**两派发通道**:
- **In-harness**（Sonnet subagent）：organizer 直接 spawn，`.claude/rules/*` 自动可见 → 适合 rule-heavy 任务（Java Tool-Skill / Python parity port / rule-aware review）
- **Out-of-harness**（Codex/Composer：organizer 出卡 → Steve courier）：无 `.claude/rules`，brief 卡**必须自包含**相关规则摘要（否则必翻车）→ 适合可完整 brief 的纯执行

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
| **规则重 in-harness 执行**（新 Java Tool / Flyway 编号检查 / rule-aware code review / Python parity port 机械修复） | **Sonnet in-harness** | `.claude/rules/*` 自动可见 → 不会因缺上下文翻 12 条 Java port 规则；比 Opus 省 20x 额度 |
| **Java AI Tool-Skill 意图路由 · Python↔Java parity port（首次判断/架构）** | **Opus**（或 Opus 写死严格 brief 后给 Sonnet/Codex） | 有 `.claude/rules/ai-intent-tool-skill-architecture.md` / `python-java-port.md` 硬规则(Decimal/Map.of order/Lombok null/HALF_UP 等 12 条), 易踩; 首次需判断力 |
| 🔒 Flyway/migration/schema · 权限/RLS/多租户/业态隔离 · 架构/跨模块重构/新实体 · 上线前 diff 终审 | **Opus 把关** | 见下方⛔红线 |
| 同一问题某模型修 2 轮还没好 | 切 **Opus** 做 root-cause review | 不让一个模型一直撞墙; 别盲改 |
| 某模型改乱了 / 你不放心 | **Opus** root-cause review (先停下判断, 不继续盲改) | |

> 模型名以 Steve 当前工具箱为准(Cursor Composer / OpenAI Codex / 本 Claude chat / Claude Sonnet subagent)。名字变了路由逻辑不变 —— 关键是"执行 vs 判断"分层 + 有没有 `.claude/rules` 上下文。

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

Opus 已经实际试过且明确卡住/wobble(不是"我预测它会失败")?
  → 卡死调试 Opus 修 2 轮没好: ✅ 应当派 `fable` subagent 升级(别硬撞第 3 次)
  → 难架构/模糊框架 Opus xhigh 已试且两版结论打架, 且 stakes 高: ✅ 派 `fable` subagent 单点
  → 🔒 终审里"不可逆/高爆炸半径 + 小 diff"窄子集(prod 迁移/RBAC/RLS/资金): ✅ 派 `fable`
  → ⛔ 否则一律 Opus; 没观察到 Opus wobble 不许预先升; Fable 5 不进执行/分诊/批量/fan-out/大 diff 终审
  → 频次闸: session 内个位数次; 想点第 2 次 → 先自检是不是 brief/需求没框清(回去修 brief, 别升模型)
```

---

## 第二路由轴: Effort + Orchestration

> 第一轴=模型(Opus/Sonnet/Codex/Composer)。第二轴=两个独立决定: 推理深度(effort) + 编排形式(orchestration)。
> 三轴都乘进 Opus 配额(cost ≈ 轮数 × effort × context), 默认保守、按需升级。

### Effort (Claude harness 旋钮; 只对 Opus/Sonnet 有效)

| 情况 | effort |
|---|---|
| 日常 Opus | `high` (默认; Opus 4.8 在此已几乎总是深想) |
| 单个难 turn | 该 prompt 加 `ultrathink` (只点一轮, 最省) |
| 长自主 session(30min+) / 真模糊架构 | `xhigh` |
| `max` | ⛔ 破玻璃; 实测 xhigh 不足才用; 稀缺 Opus 配额杀手 |
| Sonnet | `high` 封顶 (xhigh 无收益) |
| Codex/Composer | organizer 在 brief 里"建议", 设不了 |

**Effort ≠ Max 订阅**: 订阅控制用哪个模型/多少额度; effort 控制每轮想多深。两轴正交。

**⚠️ Organizer 是 effort 分配者，不是满载消费者**: 常驻 max effort 去做廉价路由分诊 = 用核弹价钱决定"派给谁" = 烧周额度最快。Organizer 用 `high` 分诊，把 `xhigh`/`ultrathink` 分配给真难的 turn。

### Orchestration (从便宜到贵, 按需上调)

`inline`(默认) → `单 subagent`(侧产出污染 Opus 主上下文/需工具隔离) → `workflow`(10+ agent 交叉核验/跨文件广覆盖) → `ultracode`(常驻 workflow-by-default、最大彻底、不计 token)

- **铁律**: fan-out 时 workers 跑 Sonnet+默认 effort, 只 Opus 编排者 high。**cost = agents × effort, 乘不是加。**
- **`ultrathink`(深化一个脑) ≠ `ultracode`(开很多脑)**: 名字像, 但前者 effort 轴顶、后者 orchestration 轴顶。
- **反例**: 概念咨询/单点问题/对话轮 → inline 或单 subagent, **哪怕 ultracode 关键词出现也别默认上 workflow**。

### 组合速查 (模型 × effort × orchestration)

| 任务 | 模型 | effort | orchestration |
|---|---|---|---|
| Codex 执行(有 spec) | Codex | default(brief 建议) | inline |
| Sonnet in-harness 执行 | Sonnet | high | inline |
| Composer UI/样式 | Composer | default | inline |
| Opus 需求 framing(低歧义) | Opus | high | inline |
| Opus 架构决策(真有疑问) | Opus | xhigh | inline |
| Opus 高风险门控 review | Opus | xhigh | 单 subagent(read-only 隔离) |
| Opus 单个难点 | Opus | high + `ultrathink` 点该轮 | inline |
| 广覆盖审计/迁移 | Opus 编排 + Sonnet workers | xhigh(Opus)/high(workers) | ultracode → workflow |
| Opus 卡死升级(修 2 轮没好) / 难架构 Opus 已 wobble / 🔒 不可逆小-diff 终审 | Opus 编排 + **Fable 5** 单点 | Fable 5 走 model 轴(非 effort 档) | 单 `fable` subagent(read-only 隔离) |

> **Fable 5 不在 effort 表里**: effort 旋钮只对 Opus/Sonnet 有效; Fable 5 是 model 轴的破玻璃顶层, 由 organizer 通过 `fable` subagent 派出, 不是给 organizer 本体加的 effort 档。台账 `model` 列允许填 `fable`(仅四落点)。

### 台账加两列

现有 dispatch 条目加 `effort` + `orchestration`（见 `docs/dispatch/ACTIVE.md`）:

```
| ID | 任务 | model | effort | orchestration | 分支 | scope 锁 | 状态 | PR | 阻塞 |
```

### Brief-vs-do 叠加 effort

"写 xhigh 任务的 brief+review, 比 Opus 直接 xhigh 做更省配额吗？" 能完整 brief → 派; brief 本身就要 xhigh Opus 才写得清 → Opus 直接做。

---

## 审查分层

| 场景 | 审查者 |
|---|---|
| 例行 review（无风险改样式/lint/补测试/无红线依赖） | **Sonnet**（rule-aware in-harness，.claude/rules 自动可见，省 Opus 额度）|
| 🔒 Risky review（权限/RLS/迁移/业态/架构/上线前 diff 终审） | **Opus**（organizer 本体，不可外包）|

---

## 反 pattern (绝对禁止)

- ❌ 三个模型同时改同一文件 → 互相覆盖(见 `concurrent-edit-safety.md`)
- ❌ 执行者从 feature 分支直接部署 prod → 覆盖别人的 prod jar(5/30 事故)
- ❌ brief 卡假设别的 chat 知道"我们刚才聊的" → 卡必须自包含
- ❌ 红线任务交给 Composer/Codex 自审自部署 → 数据泄漏/覆盖风险
- ❌ 按固定额度比例硬塞 Opus 干低价值执行 / 硬塞 Composer 做高风险决策
- ❌ Opus 常驻 max effort 做路由分诊 → 用核弹价钱决定"派给谁"，几天撑爆周限额
- ❌ Out-of-harness Codex/Composer brief 卡不内联相关 `.claude/rules` 摘要 → Java parity/Tool-Skill 规则缺失必翻车
- ❌ Java Tool-Skill / Python parity port 直接派 Codex（无规则上下文）→ 用 Sonnet in-harness 或 Opus 把规则内联进 brief
- ❌ fan-out workflow 时 workers 也跑 Opus/xhigh → cost = agents × effort 是乘，炸配额
