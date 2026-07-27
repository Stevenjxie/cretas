---
name: multi-model-dispatch
description: 多模型分发路由规范(Opus/Sonnet/Fable/Codex/Composer)。触发场景:出计划/设计末尾要产「分发卡」;决定某个任务派给哪个模型、用什么 effort、什么 orchestration(三轴路由);判断要不要升级 Fable 5(earned-not-predicted/预授权直通/频次闸);写自包含 brief 卡给 Codex/Composer;审查分层(例行 review vs 🔒 risky 终审);🔒 红线任务(prod 部署/migration/权限 RLS/架构/上线终审)的收尾约束。凡涉及"派活给哪个模型/多 chat 并行分工/分发卡/brief 卡"必读本 skill。
---

# 多模型分发路由规范 (Multi-Model Dispatch)

**最后更新**: 2026-07-28
**触发**: Steve 要"Claude 出计划时直接产出可分发的 task 输出, 我去派给其他 chat, 并指定哪些给 GPT-5.5·Codex / 哪些给 Composer 2.5"。增补：Sonnet 执行层 + 三轴路由（模型/effort/orchestration）+ 预算均衡注记 + 两通道 + 审查分层。**2026-06-10**: Fable 5 上线(2x Opus 消耗)→ 加 model 轴破玻璃顶层 + Fable 5 定位铁律(organizer 本体不换 Fable 5, 只派 `fable` subagent 做四落点单点)。**2026-06-10 晚 v2(Steve 拍板)**: 升级闸修订 — 卡死阈值 2轮→**1轮认真尝试**; 新增**三类预授权直通**(prod 事故计时中/同族前科/不可逆小diff终审)可跳过 Opus 直接 Fable; Opus 失败轮产物必须回收进 fable brief。**2026-07-28 v3**: 判断层 Opus 4.8→**Opus 5**(同价 $5/$25, 官方定位为相对 4.8 的 step-change); **Sonnet 5 effort 天花板纠错**(原"xhigh 无收益"的证据来自 Sonnet **4.6** — 那代根本没有 xhigh 档); 附录加三模型 API 表面对照(**参考, 明确不构成升级理由**)。
**关系**: 本规则同时回答"能不能并行"与"每块并行工作派给哪个模型 + 怎么物理隔离 + 怎么交接回 main"（前身 `.claude/rules/parallel-work-analysis.md` 已于 2026-07-28 删除并入本 skill）。隔离铁律继承 `.claude/rules/worktree-and-main-only-deploy.md` + `.claude/rules/concurrent-edit-safety.md`。编排顶层入口 → `organizer` skill。

---

## 核心理念

```text
Fable 5        = 破玻璃判断顶层 (2x Opus 消耗 — $10/$50 vs Opus 5 $5/$25, 换代后仍**精确 2x**, 所以下方经济学推导原封不动成立; 比 Opus 还稀缺; earned-not-predicted v2: Opus 1 轮认真尝试没收敛即升 + 三类预授权直通, session 个位数次)
Opus 5         = 总工 / 架构师 / 高风险决策 / 上线前终审 (贵但稳, 负责判断对不对) — organizer 本体常驻这档
Sonnet 5       = Claude 侧默认执行层 + 主力工蜂 (model id `claude-sonnet-5`, 与 Opus 4.8 同代/同 Jan-2026 截止, 1M ctx / 128K 输出 / adaptive thinking, $3/$15 且 8-31 前引入价 $2/$10, 比 Opus 便宜 ~2.5x 且更快, 便宜 20x-桶, rule-heavy in-harness 自动加载 .claude/rules). 2026-07 Sonnet 4.6→5 一大跳: 承接面比 4.6 显著扩大, 不再只是"机械执行层"—— 判断密集/rule-heavy/大部分 bug 修复都可交给它, Opus 收窄到最硬 🔒 判断 + 出货闸(见下 §代码执行层重平衡)
GPT-5.5·Codex  = 复杂执行 + CLI/E2E/构建 + 第二审查 (强执行, 复杂工程操作)
Composer 2.5   = 独立 UI / 样式 / lint / 补测试 (Cursor 内便宜耐用)
```

**预算均衡注记**: Claude Max 20x + GPT 10x(较小桶) + Cursor 三个都是 flat → **铺开用三个、别撑爆更小的 GPT 10x**。Claude 侧执行优先走 Sonnet 拉长 20x（省 Opus 周额度），Opus 集中用在需求框架/🔒终审/难架构。

### ⛔ Fable 5 定位铁律 (2026-06-10 增补: Fable 5 上线)

**Fable 5 是比 Opus 还稀缺的资源, 不是"更好的 Opus organizer"。** Fable 5 = **2x Opus 消耗** → 烧周限额速度是 Opus 的两倍; Opus 本就"纯 Opus 一周绝对不够"。所以保护 Opus 的纪律(`分配者不是满载消费者`)对 Fable 5 **加倍执行**。

| 必须 | 禁止 |
|---|---|
| **organizer 本体保持 Opus 5 + high** — Fable 5 由 organizer 通过 `fable` subagent (Agent tool model override 支持) 在**单点**派出, 自己 body 不动 | ❌ 把整个 organizer 换成 Fable 5 → 每轮廉价路由分诊 ×2 = `满载消费者` 反模式 ×2, 几天炸周限额 |
| **earned-not-predicted(v2)**: 默认仍是 Opus 先试 — 但阈值降为 **1 轮认真尝试**(打完没收敛 + 能说清卡在哪 → 升, 不撞第 2 轮); 例外 = 三类**预授权直通**(见下), 触发条件客观可证伪, 不是"我觉得难" | ❌ 预授权三类之外, 预先"为了保险用最好的模型"。没观察到 Opus 卡住就不许升 |
| **model 轴新顶**: 难判断升级阶梯 = Sonnet 5 → Opus 5 → **Fable 5**(effort 在每档内作次级旋钮)。Opus 5 × xhigh 已试且 wobble → 升 Fable 5, 而不是停在 Opus 继续烧 effort | ❌ 任何执行(Sonnet/Codex/Composer)/路由分诊/批量机械/fan-out workers。`cost = tokens × 2x`, 体量工作进 Fable 5 是灾难 |

#### 防滥用闸 + 防荒废闸 (闸要自我执行, 不靠 organizer 自律)

**五落点, 按性价比排序(不是"终审最强"——见下纠错):**

| 落点 | 触发(客观优先) | 注意 |
|---|---|---|
| ① **卡死调试升级**(最干净) | Opus **1 轮认真尝试**没收敛且能说清卡在哪 → **客观触发, 应当升**(v2: 不撞第 2 轮 — 第 2 轮往往是不甘心的重复撞墙) | 升级时把 Opus 轮产物(问题框架/repro/已排除假设)**回收进 fable brief**, 抵消 2x rediscovery 惩罚 |
| ② **真有判断模糊的难架构选型** | Opus **xhigh 已试且两版结论打架/拿不准** 才升 | 没试过 Opus 不许直接 Fable 5 |
| ③ **模糊高风险需求框架** | 同②: Opus 先框, 框不清且 stakes 高才升 | ⚠️ 框架常 token 量大, 2x 贵; Opus 够就别升 |
| ④ **🔒 终审里"不可逆/高爆炸半径"窄子集** | prod 迁移 / RBAC·RLS·多租户数据泄露 / 资金路径, **且 diff 小** | ⚠️ **不是每个终审**。大 diff 终审用 Opus + 对抗 fan-out 更划算(见纠错) |
| ⑤ **战略纠偏审计**(course-correction; 2026-06-10 Steve 加, 前瞻型) | 多线程程序在**投入大 effort 前**的战略拐点: 重心疑似飘了 / 即将投大 effort 但 ROI 不确定 / 需 reconcile 历史决策。**Steve 直接点名"审一下接下来怎么做"亦属此**(用户请求即客观触发) | read-only **战略** review(非 code-review): "下一步什么顺序 / 方向稳不稳 / 该砍什么"。Opus 自己能规划, 但 Fable 独立顶层视角专抓 **①优先级反转 ②找回被遗忘的历史决策 ③剪 over-engineering / 沉没成本惯性**。给全程序状态 + 历史决策指针, 让它 reconcile。**仍 earned**: 真拐点(投大 effort 前)非例行 planning; 仍频次闸 |

**⛔ 纠错(第一稿的错)**: "🔒 终审 = 最强候选" **站不住**。organizer 本体(Opus)做终审时已持有 diff context; 交给全新 `fable` subagent = **2x 费率 + 从头 rediscovery context** 双重惩罚。终审是 Fable 5 性价比**最差**的落点之一(尤其大 diff)。真正最强是 ① 卡死升级(客观触发 + 想要异模型视角)。

**预授权直通 (2026-06-10 v2, Steve 拍板)**: 以下三类**不需要先跑 Opus**, 可直接派 `fable` —— 口子窄且触发条件可证伪:
1. **prod 事故计时中**: 真客户被 block, 失败轮的代价是小时不是 token(期望成本算式翻转)。
2. **同族前科**: 同类问题此前已实证 Opus 打不动、靠升级才解(有台账/memory 记录可引用, 不是"感觉像")。
3. **不可逆 + 小 diff 终审**(即落点④: prod 迁移 / RBAC·RLS / 资金路径)。

经济学根据(为什么默认仍 earned): 设 Opus 轮=1、Fable 轮=2, p=Opus 1-2 轮内解掉的概率 → earned 期望成本 ≈ p×1+(1-p)×4, p>~50% 时 earned 更省。本项目实证 base rate 高(绝大多数"看着难"的问题 Opus 一轮即倒), 且预测式升级不可证伪会类目蔓延。预授权三类 = p 已知很低、或失败代价不在 token 维度的情形。

**频次闸(对标 max effort 的"几乎不用")**: Fable 5 应是 **session 内个位数次**的破玻璃动作, 不是每个 risky 任务都点。**想点第 2 次就停下自检**: 是不是在用 model 轴掩盖 brief 没写清 / 需求没框清 —— 那应该回去修 brief, 不是升模型。

**⚖️ 防过省校准 (2026-07-06 Steve: "改用 fable 的时候就用 fable, 不要太省")**: 频次闸"个位数次"是**上限不是配额**。防滥用闸防的是**预测式**滥用(没观察到卡住就升 / 拿 model 轴掩盖 brief 没写清), **不是**压制 earned 命中 —— 别把"想点第 2 次先自检"退化成"能不用就不用"。earned 车道清楚命中(尤其**大批修复刚落地 → `fable` read-only diff-hunt** 这条同族前科预授权直通)就**主动派, 别等 Steve 点、别为省周额度硬留 Opus**。上闸(别预测式滥用)+下闸(别过省荒废)方向相反都要守。实证: 2026-07-06 无限测试 campaign Wave 1 该主动派 diff-hunt 却等 Steve 点才派 = 过省。见 memory `feedback_fable_dont_be_too_stingy`。

**防荒废(别变死信)**: 上面 ① 是 **affirmative "应当升"** —— Opus 修 2 轮没好时**别为省额度硬留 Opus**, 那是 Fable 5 存在的意义。worked examples:
- ✅ **会点**: 5/30 RBAC 角色转发那种"改对一处 / prod 营收归零"的 permission 判断, Opus xhigh 给了两版互相矛盾的结论 → 派 1 个 `fable` subagent 单点定夺。
- ✅ **会点(⑤ 战略纠偏)**: 6/10 chart-insight 程序多线(铺开/seeding/M4/自有模型)将投大 effort 前, Steve 点"审一下接下来怎么做" → `fable` 战略 review 抓出**资源分配反转**(重心飘向 flywheel/自有模型, 而最高价值"铺到36面"没开工) + **找回被遗忘的 May-31 vertical-model verdict**(自训模型早已 trigger-gated) + **砍 exotic-6/seeder 扩量/模型工作流**。比 Opus 自己规划多了"独立顶层视角戳穿沉没成本惯性"。
- ❌ **不点**: 例行 risky review(逻辑直白的带迁移 PR)、token 量大的需求框架、任何执行/批量 —— 全留 Opus/fleet。**⑤ 也不是每次出计划都点** —— 只在真战略拐点(投大 effort 前 / 重心疑似飘 / 需 reconcile 历史)；日常 planning Opus 自己做。

**①-④ 是反应型(Opus 试过/卡住/风险), ⑤ 是前瞻型(投大 effort 前先验方向)** —— 两者都 earned(非预测式滥用), 都频次闸。⑤ 的"earned"= 真拐点而非例行规划; 它独有的价值是**纠偏**(catch drift + reconcile 历史决策 + 剪 over-engineering), 不是解难题。

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

**触发**: 我(Claude/Opus)每次出**计划 / 设计**, 末尾**默认**追加一段分发卡。

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
| 🆕 **判断密集/微妙语义/🔒红线 代码自写**(DB事务&并发/Hibernate语义/报工模型/Java Tool-Skill/parity/常规写库) | **Sonnet 5 in-harness**(独立对抗审计把关) | **2026-07-02 重平衡(Sonnet 5 上线)**: Sonnet 5 与 Opus 4.8 同代, 这类现可派 Sonnet 5(规则自动可见), Opus 只做出货闸终审. 原 2026-06-11"不派 Sonnet"的证据是 Sonnet **4.6**(getRecipe回归/密码seed/Flyway乱序), 不代表 5 |
| 🔒🔒 **最硬红线子集 代码自写**(成本/财务口径 · prod 迁移/Flyway 撞号 · 权限/RLS/多租户/业态隔离 · 撤回回退 · 资金路径) | **暂留 Opus 自做** → 待 Sonnet 5 实测证明后放行 | 本 session 抓修的微妙 🔒 bug(shippedQuantity污染财务/出成率双计/honest-null泄漏)只有独立对抗审计逮到; **别凭 marketing 直接全放**——拿真实此类 🔒 修复让 Sonnet 5 做 + 独立审计, 过了才把这一子集也放行到 Sonnet 修复车道. Opus 写后二次评估 inline(小+在context) vs subagent(大/可并行) 同前 |
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

预授权直通(v2, 可跳过 Opus 直接 `fable`)?
  → prod 事故计时中(客户被 block, 失败轮代价=小时): ✅ 直接派 `fable`
  → 同族前科(同类问题有台账/memory 记录 Opus 打不动): ✅ 直接派 `fable`
  → 🔒 不可逆+小 diff 终审(prod 迁移/RBAC/RLS/资金): ✅ 直接派 `fable`
否则 Opus 先试 **1 轮认真尝试**(v2, 原 2 轮):
  → 没收敛且能说清卡在哪: ✅ 升 `fable`(不撞第 2 轮; Opus 轮产物回收进 brief)
  → 难架构/模糊框架 Opus xhigh 已试且两版结论打架, 且 stakes 高: ✅ 派 `fable` subagent 单点
前瞻型(⑤ 战略纠偏, 投大 effort 前):
  → 多线程程序将投大 effort 且(重心疑似飘 / ROI 不确定 / 需 reconcile 历史决策), 或 Steve 点名"审接下来怎么做": ✅ 派 `fable` read-only 战略 review(给全程序状态+历史决策指针)
  → ⛔ 例行出计划不点(Opus 自己规划); 只在真拐点
  → ⛔ 其余一律 Opus; Fable 5 不进执行/分诊/批量/fan-out/大 diff 终审
  → 频次闸: session 内个位数次; 想点第 2 次 → 先自检是不是 brief/需求没框清(回去修 brief, 别升模型)
```

---

## 第二路由轴: Effort + Orchestration

> 第一轴=模型(Opus/Sonnet/Codex/Composer)。第二轴=两个独立决定: 推理深度(effort) + 编排形式(orchestration)。
> 三轴都乘进 Opus 配额(cost ≈ 轮数 × effort × context), 默认保守、按需升级。

### Effort (Claude harness 旋钮; 只对 Opus/Sonnet 有效)

| 情况 | effort |
|---|---|
| 日常 Opus 5 | `high` (默认; thinking 默认开)。但 coding/agentic **起步 `xhigh`** 更对(官方建议), 然后**往下扫** — Opus 5 的 `low`/`medium` 强得反常, 例行活别默认蹲 high |
| 单个难 turn | 该 prompt 加 `ultrathink` (只点一轮, 最省) |
| 长自主 session(30min+) / 真模糊架构 | `xhigh` |
| `max` | ⛔ 破玻璃; 实测 xhigh 不足才用; 稀缺 Opus 配额杀手 |
| Sonnet 5 | 默认 `high`; **最硬的 coding/agentic 可上 `xhigh`** — 2026-07-28 纠错: 原写"xhigh 无收益"的证据来自 Sonnet **4.6**(那代根本没有 xhigh 档), Sonnet 5 有这档且官方推荐用于最硬 coding/agentic |
| Codex/Composer | organizer 在 brief 里"建议", 设不了 |

**Effort ≠ Max 订阅**: 订阅控制用哪个模型/多少额度; effort 控制每轮想多深。两轴正交。

**⚠️ Opus 5 的输出长度不能靠降 effort 压**: 降 effort 只动思考深度, **不可靠地**缩短可见输出。嫌啰嗦要在 prompt 里明说(官方实测一句简洁指令砍 ~20% 长度) —— 别用 effort 当话痨旋钮。

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
| Sonnet in-harness 执行 | Sonnet 5 | high(最硬 coding/agentic 可 xhigh) | inline |
| Composer UI/样式 | Composer | default | inline |
| Opus 需求 framing(低歧义) | Opus | high | inline |
| Opus 架构决策(真有疑问) | Opus | xhigh | inline |
| Opus 高风险门控 review | Opus | xhigh | 单 subagent(read-only 隔离) |
| Opus 单个难点 | Opus | high + `ultrathink` 点该轮 | inline |
| 广覆盖审计/迁移 | Opus 编排 + Sonnet workers | xhigh(Opus)/high(workers) | ultracode → workflow |
| Opus 卡死升级(1 轮认真尝试没收敛) / 难架构 Opus 已 wobble / 预授权直通(prod 事故计时/同族前科/🔒 不可逆小-diff 终审) | Opus 编排 + **Fable 5** 单点 | Fable 5 走 model 轴(非 effort 档) | 单 `fable` subagent(read-only 隔离) |

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

## 附录: 三模型 API 表面对照 (2026-07-28)

> ⛔ **本表是 API 表面差异, 不构成升级理由。** 参数齐平 ≠ 能力齐平 —— 参数表结构上就表达不了能力差, 别看着"参数差不多"就下调 Fable 闸, 也别看着某档"适合"就上调。派 Fable 5 的触发器**只看上面五落点 + 三类预授权直通**(客观可证伪), 不看"这活看着适合 Fable"。"适合跑几十分钟没人盯的自主任务"这类叙事是**预测式**理由 = 本 skill §Fable 5 定位铁律明令禁止的那类。

| | Sonnet 5 | Opus 5 | Fable 5 |
|---|---|---|---|
| model id | `claude-sonnet-5` | `claude-opus-5` | `claude-fable-5` |
| 价格 /MTok | $3/$15(8-31 前引入价 $2/$10) | $5/$25 | $10/$50 = **精确 2x Opus** |
| 上下文 / 输出 | 1M / 128K | 1M / 128K | 1M / 128K |
| thinking 默认 | 开 | 开 | **永远开, 关不掉** |
| 能否关 thinking | ✅ 可以 | ⚠️ 仅 effort ≤ `high`(配 xhigh/max → 400) | ❌ 任何 effort 都 400 |
| effort 档 | `low`–`max`(**含 xhigh**) | `low`–`max` | `low`–`max` |
| Claude Code fast mode | ❌ | ✅ | ❌ |
| 数据留存要求 | 无 | 无 | **须 30 天留存**(ZDR 组织所有请求 400) |

三者共同: 原始 chain-of-thought 都不返回(只能拿摘要); 都可能因安全分类器返回 `stop_reason:"refusal"`。

**唯一影响派发决策的能力差(有据, 非玄学) = 委派方向相反**: Opus 5 的官方**已知缺陷**是**过度**委派 subagent(迁移指南专门配了限流 prompt, 含"除非明确要求绝不超过 20 个并行 agent"); Fable 5 反过来被描述为并行 sub-agent"可靠, 建议频繁 + 异步用"。落到本 skill 的操作含义是 **fan-out 编排用 Opus 5 时要主动装刹车**(§Orchestration 铁律已覆盖: workers 跑 Sonnet + 默认 effort, 别放开 agent 数) —— 这条结论是"Opus 5 需要限流", **不是**"所以该换 Fable"。

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
- ❌ 拿"这活看着适合 Fable 5"或附录那张能力对照表当升级理由 → 预测式滥用(附录已警告)。触发器只有**五落点 + 三类预授权直通**，参数表不是闸
