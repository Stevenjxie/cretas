# Thin-Opus Organizer 编排模型

**最后更新**: 2026-06-10
**触发**: 2026-05-30 青花椒 RBAC 修复在 prod 被并发 session 部署覆盖（总营收回 ¥0）；4/8 deploy 脚本被并发 session 覆盖只剩 29 行；4/11+4/28 commit scope 被并发 staged 文件污染。根治：单一前门(intake) + 单一出货闸(ship gate)。**2026-06-10**: Fable 5 上线(2x Opus)→ 加 Fable 5 门槛(organizer 本体仍 Opus 4.8 + high; earned-not-predicted: 只在 Opus 已实际试过且卡住后派 `fable` subagent, session 个位数次)。**2026-06-10 晚 v2(Steve 拍板)**: 卡死阈值 2轮→1轮认真尝试 + 三类预授权直通(prod 事故计时/同族前科/不可逆小diff终审) + Opus 失败轮产物回收进 fable brief。
**关系**: 本规则是编排顶层入口。三轴路由详表 → [`multi-model-dispatch.md`](./multi-model-dispatch.md)。隔离铁律 → [`worktree-and-main-only-deploy.md`](./worktree-and-main-only-deploy.md) + [`concurrent-edit-safety.md`](./concurrent-edit-safety.md)。

---

## 拓扑：单一前门 + 单一出货闸

```text
Steve 想法
    ↓
┌──────────────────────────────────────┐
│   Opus Organizer (长命 chat)          │
│   = 唯一 intake 前门                  │
│   = 唯一 ship gate 出货闸 🔒           │
│                                      │
│  接收 → 拆解 → 写 brief → 维护台账     │
│  ← PR 回来 → 终审 → merge → 部署       │
└────────────┬─────────────────────────┘
             │ 分发 brief 卡
   ┌─────────┼──────────────┐
   ↓         ↓              ↓
Sonnet    Codex          Composer
(in-harness) (out-of-harness) (out-of-harness)
   │         │              │
   └─────────┴──────────────┘
             ↓ PR off origin/main
        Opus 终审 + 从 main 部署 prod
```

**为什么单一出货闸能治并发覆盖**：多 session 并发部署 prod = last-write-wins（5/30 RBAC ¥0 复现路径）。只有 Opus organizer 能发出 prod 部署指令，其他 session 一律"做到 PR 停"，物理上消除 last-write-wins 竞争。

---

## Organizer 角色定义

### ✅ 只做（每轮短、高杠杆）

| 角色 | 说明 |
|---|---|
| **需求框架（最高价值）** | 把 Steve 模糊想法框成边界清晰的 spec；辨别真需求 vs 假设（如 Jun 4 邓总"混合直营+加盟"被纠正为单店直营 MVP）|
| **任务拆解 + brief 写作** | 写自包含 brief 卡（fleet 看不到本 chat 上下文，卡必须带全开工所需一切）|
| **台账维护** | 每轮只读/写 `docs/dispatch/ACTIVE.md`，不攒全历史；scope 锁地图防撞 |
| **难架构决策** | 真有判断模糊的架构选型，`xhigh` 深想 |
| **Keystone 代码** | 小 + 微妙 + 高风险 + 已在 context（见 brief-vs-do 测试）|
| **🔒 Risky 终审** | PR 终审（权限/迁移/业态/跨模块重构）；唯一 merge+prod 部署者 |
| **Scope 仲裁** | 两个 worker 改同文件 → 决定谁先谁后 / 怎么切 |

### ⛔ 绝不做

| 禁止 | 原因 |
|---|---|
| 批量机械执行（写 100 行脚手架 / 批量 CRUD / 大段 doc） | Fleet 更便宜；Organizer 做 = 烧稀缺 Opus 额度 |
| 例行 review（无风险的代码美化/lint） | Sonnet 胜任；Opus 审是过杀 |
| 攒全部历史在 context | context 越大 organizer 越贵越慢；台账是代替上下文的"外存" |
| 常驻 max effort | 见下方 ⚠️ 纠正 |

### ⚠️ 关键纠正：Organizer 默认 `high`，**是 effort 分配者，不是满载消费者**

**错误观念**：Organizer = "用 max effort 把关所有事"。

**正确**：Organizer 用 `high`（日常），把 `max`/`xhigh` **分配出去**——多数分给遇到的那个难判断 turn，而非自身常驻。

- 常驻 max effort 去做"派给谁"的廉价路由分诊 = 用核弹价钱决定"外卖还是堂食" = 烧周额度最快。
- Organizer 在 brief 里**建议** fleet 的 effort，fleet 自己跑（Codex/Composer 里 organizer 设不了 effort）。
- `ultrathink` = 外科式只深化单个 turn（Opus 拿深度的默认方式）；不是 orchestration，是 effort 轴单点工具。

三轴路由详表 → [`multi-model-dispatch.md`](./multi-model-dispatch.md)。

---

## Fleet：两条派发通道

| 执行者 | 桶 | 擅长 | 通道 |
|---|---|---|---|
| **Sonnet 5** (`claude-sonnet-5`) | Claude 20x（便宜, ~2.5x 便宜过 Opus, 引入价 $2/$10 至 8-31） | **主力工蜂** — 与 Opus 4.8 同代(Jan-2026 截止/128K 输出/adaptive): rule-heavy in-harness(Java Tool-Skill/parity/rule-aware review/机械 doc) + **判断密集执行 + 大部分 bug 修复**(2026-07 rebalance, 承接面比 4.6 大扩)。最硬 🔒(成本财务口径/prod迁移/权限RLS/业态隔离/撤回/资金)暂留 Opus 待实测证明 | **in-harness**：organizer 直接 spawn subagent，.claude/rules 自动可见 |
| **Codex** | GPT 10x（独立 sub，较小桶） | 可 brief 的纯后端 / CLI / E2E / 构建 / TDD | **out-of-harness**：organizer 出卡 → Steve courier → worker 不加载 .claude/rules，brief **必须自包含** |
| **Composer** | Cursor sub | 独立 UI / 样式 / lint / 补测试 | out-of-harness：同上 |
| **Opus** | Claude 20x（周额度稀缺） | 判断 / 门控 / 需求框架 / keystone / 🔒 终审 | 本体 |
| **Fable 5** | Claude 20x（**2x Opus, 比 Opus 还稀缺**） | 破玻璃判断顶层: Opus **1 轮认真尝试**没收敛后的单点(① 卡死升级最干净 / ② 难架构·框架 Opus 已 wobble / ③ 不可逆小-diff 终审)；**v2 三类预授权直通可跳过 Opus**(prod 事故计时/同族前科/③)。session 个位数次 | organizer 派 `fable` subagent（**organizer 本体不换 Fable 5**） |

### In-harness vs Out-of-harness 关键差异

```text
In-harness (Sonnet subagent):
  - .claude/rules/* 自动加载 → 知道 Decimal/Map.of/Flyway/等 12 条 Java port 规则
  - 适合：需要规则意识的任务（Java Tool-Skill / Python parity port / review）

Out-of-harness (Codex/Composer 卡 + Steve courier):
  - 无 .claude/rules → brief 卡必须把相关规则内联进去（否则必翻车）
  - 适合：可完整 brief 的纯执行（UI / 构建 / E2E / 无规则依赖的后端）
  - ⚠️ Java Tool-Skill + Python parity port → 用 Sonnet in-harness，或 Opus 把规则摘要内联进 Codex brief
```

---

## 代码执行层路由（2026-06-11 Steve 修订：速度+质量 > token 经济）

> **触发**：本 session Sonnet 写判断密集/红线代码反复栽——getRecipe LazyInit 回归 / G4 把已知密码账号 seed 进 prod 真客户租户 / Flyway 乱序号 / PaymentRequestController 误报。全靠 Opus 终审兜住，但代价是返工 + 救回被关 PR + 迭代，拖慢节奏。Steve：质疑 Sonnet 写代码能力，**别太省 token，速度质量优先**（但机械活仍 Sonnet）。
>
> **⚠️ 2026-07-02 重平衡（Sonnet 5 上线）**：上面的证据是 **Sonnet 4.6**。Sonnet 5(`claude-sonnet-5`)与 Opus 4.8 **同代**(Jan-2026 截止/128K 输出/adaptive)，承接面大扩 —— **判断密集/微妙语义/一般 🔒 代码现默认可派 Sonnet 5**(in-harness 规则自动可见 + 独立对抗审计把关)，Opus 收窄到出货闸终审。**但最硬 🔒 子集**(成本/财务口径 · prod 迁移/Flyway 撞号 · 权限/RLS/多租户/业态隔离 · 撤回回退 · 资金路径)**暂留 Opus**，直到用真实此类修复 + 独立审计实测 Sonnet 5 过关，才放行到 Sonnet 修复车道。**别凭 marketing 直接全放** —— 本 session 抓修的微妙 🔒 bug(shippedQuantity污染财务/出成率双计/honest-null泄漏)只有独立对抗审计逮到，先验证再全托。下表按此更新。

### 第一层：按「判断密度」选模型（Sonnet 5 后承接面上移）

| 代码性质 | 执行层 | 例 |
|---|---|---|
| **机械 / 低判断 / 规则重** | **Sonnet 5** | 脚手架、按 spec CRUD、parity port、文档/测试样板、按设计 UI、规则感知机械 review |
| **判断密集 / 微妙语义 / 一般 🔒** | **Sonnet 5**（独立对抗审计把关；2026-07 从 Opus 下放） | DB 事务&并发、Hibernate/JPA 语义、报工模型、常规写库、Java Tool-Skill、parity port |
| **🔒🔒 最硬红线** | **Opus 自做** → 待 Sonnet 5 实测证明后放行 | 影响 prod 的迁移/Flyway 撞号、权限/RLS/多租户/业态隔离、成本/财务口径、撤回回退、资金路径 |
| **最难 / Opus 卡住 / 微妙红线 keystone 速度质量都关键** | **Fable**（earned 闸照旧） | 见 Fable 门槛 |

**token 经济**：Sonnet 5 便宜 ~2.5x 过 Opus 且引入价 $2/$10(至 8-31)、同代能力 → 主力铺 Sonnet 5 省 Opus 周额度更划算；Opus 集中在最硬 🔒 判断 + 出货闸。（原 2026-06-11「判断密集别下压 Sonnet」是 4.6 时代结论，Sonnet 5 已重平衡如上。）

### 第二层：Opus-tier 代码 → 二次评估 inline vs Opus subagent（orchestration 轴）

决定「Opus 写」后，**再评估本体 inline 写 vs 开 Opus subagent 并行**（Steve 2026-06-11）：

| 选 | 条件 |
|---|---|
| **inline（organizer 本体）** | 小 + 已在我 context + 需判断连续性（keystone）。我已持上下文，handoff 无收益 |
| **Opus subagent（可并行/隔离）** | 多个独立红线块可并行 / 单块大且需隔离探索（否则 bloat 本体 context，破 thin-organizer）。每个 subagent 仍 Opus tier |

**thin-organizer 不变**：红线代码即便 Opus tier，**大块/可并行也开 Opus subagent**，别把实现细节塞满本体（本体留给分诊+判断+终审+台账）。延伸 brief-vs-do：小+连续性→inline；大/并行→Opus subagent。

---

## 预算现实（绑定约束）

Claude Max 20x（**Opus 按周限额**）+ GPT 10x（Codex，较小桶）+ Cursor（Composer）。三个都是 flat。

```text
2026-07-02 重平衡 (Sonnet 5 上线, 覆盖 2026-06-11 的 4.6-时代结论):
  机械/规则重 + 判断密集/一般🔒 → Sonnet 5 (同代 Opus 4.8, 独立审计把关; 便宜 2.5x 主力铺)
  🔒🔒 最硬红线 (成本财务口径/prod迁移/权限RLS/业态隔离/撤回/资金) → Opus 暂留, 待 Sonnet 5 实测证明
  出货闸终审 + 从 main 部署 prod → 恒 Opus (不可外包)
  最大杠杆 = Sonnet 5 铺量 + Opus 守最硬判断和出货闸 (先验证再把最硬🔒也放行)
错误用法 = 把 Opus 当机械执行层(写脚手架/批量文档) → 撑爆周限额
  (注: Opus 写红线代码 ≠ 错误用法, 那正是它该干的)
```

---

## Dispatch 台账（让 Thin-Opus 可行的核心机制）

文件：`docs/dispatch/ACTIVE.md`

**单一写者 = Organizer**（worker 不直接写，避免台账自身变撞车点）。Organizer 每轮**只读它**（不读全历史上下文）→ 薄、可重启、重新接手零成本。

### In-flight 表

| ID | 任务 | model | effort | orchestration | 分支 | scope 锁 | 状态 | PR | 阻塞 |
|---|---|---|---|---|---|---|---|---|---|
| T001 | 示例：KPI 看板 | Composer | default | inline | feat/kpi-ui | `web-admin/src/views/kpi/` | 🟡 in-progress | - | 等后端 T002 |

### Scope 锁地图

| 文件 / 目录 | 锁定 task | 预计解锁 |
|---|---|---|
| `web-admin/src/views/kpi/` | T001 | PR 合并后 |

**派活前查 scope 锁地图**：重叠 → 串行 or 切 scope，绝不并发改同一文件。

### Done（待清理）

合并后的 task 移到这里，每周清一次。

---

## Brief-vs-do 测试（Opus 何时亲手做 vs 派出去）

**默认派（保额度）。Opus 直接做只在四个条件同时满足**：小 + 微妙 + 高风险 + 已在 context。

判据：**「写 brief + review 比我自己做更贵吗？」** 能完整 brief → 派；brief 本身就要 `xhigh` Opus 才写得清 → 自己做。

```text
Opus 直接做（典型 pattern）:
  - 写 20 行 keystone（关键算法/安全边界/事务修复）
  - 派 fleet 写周围 200 行脚手架 + 测试 + wiring

按文件切 or 先写 keystone 再交棒 → 避免同文件撞
```

### Opus 稀缺额度优先级（2026-07-02 重平衡：Sonnet 5 上线，Opus 收窄）

```
① 模糊高风险需求框架（最高价值，防建错东西）
② 🔒 Risky 终审 + 出货闸 + 从 main 部署 prod（权限/迁移/业态/上线前 diff）— 恒 Opus，不可外包
③ 难架构（真有判断模糊的选型）
④ 卡死调试（fleet 修 2 轮没好 → Opus root-cause）
⑤ 🔒🔒 最硬红线代码自写（成本/财务口径·prod迁移/Flyway·权限/RLS/多租户/业态·撤回·资金）— 暂留 Opus，待 Sonnet 5 实测证明后放行
⑥ 小而关键 keystone（已在 context）
⑦ （机械/规则重 + 判断密集/一般🔒 → fleet=Sonnet 5，独立对抗审计把关）
```

⚠️ 注意 ⑤ 与「Opus 不当执行层」不冲突：**机械执行**(脚手架/批量doc)甩 fleet 是对的；**红线代码**自做也是对的。区别在判断密度，不在"是不是写代码"。

### Fable 5 门槛（在 Opus 之上加一档，2026-06-10 增补）

Fable 5 = **2x Opus 消耗 → 比 Opus 还稀缺**。它不是"更好的 Opus organizer"，**organizer 本体永远是 Opus 4.8 + high**（换成 Fable 5 = 每轮廉价分诊 ×2 = `满载消费者` 反模式 ×2）。

**earned-not-predicted v2（核心闸，防滥用；2026-06-10 晚 Steve 拍板修订）**: 默认仍是 Opus 先试 —— 但阈值降为 **1 轮认真尝试**（打完没收敛 + 能说清卡在哪 → 升，不撞第 2 轮，第 2 轮往往是不甘心的重复撞墙）。预测式升级仍禁止（"我觉得这超出 Opus 能力"不可证伪，类目会蔓延，等于把"常驻 max effort"的病换个轴放回来）——**例外只有三类预授权直通**（触发条件客观可证伪）。

- 🚀 **预授权直通（v2，可跳过 Opus 直接 `fable`）**: ⓐ **prod 事故计时中**（真客户被 block，失败轮代价=小时不是 token）；ⓑ **同族前科**（同类问题有台账/memory 记录实证 Opus 打不动）；ⓒ 🔒 不可逆+小 diff 终审（prod 迁移 / RBAC·RLS·多租户数据泄露 / 资金路径）。
- ✅ **应当升（affirmative，防荒废）**: ① 卡死调试 Opus 1 轮认真尝试没收敛 → 派 `fable` subagent 拿异模型视角（最干净的落点），**Opus 轮产物（问题框架/repro/已排除假设）必须回收进 fable brief**（抵消 2x rediscovery）。② 难架构 / 模糊高风险框架：Opus xhigh 已试且两版结论打架且 stakes 高。③ = 预授权 ⓒ。
- ✅ **⑤ 战略纠偏审计（前瞻型，2026-06-10 Steve 加）**: 多线程程序**投入大 effort 前**的战略拐点（重心疑似飘 / ROI 不确定 / 需 reconcile 历史决策），或 **Steve 点名"审一下接下来怎么做"** → 派 `fable` **read-only 战略 review**（给全程序状态+历史决策指针让它 reconcile）。专抓 **优先级反转 + 找回被遗忘的历史决策 + 剪 over-engineering**（实证 6/10：抓 chart-insight 重心飘向 seeding/自有模型 + 找回 May-31 vertical-model verdict + 砍 exotic-6）。**①-④ 反应型（Opus 试过/卡住/风险），⑤ 前瞻型（投大 effort 前先验方向纠偏）**。仍 earned（真拐点非例行 planning）+ 频次闸。
- ⛔ **不升**: 预授权之外没观察到 Opus 卡住；任何执行 / 分诊 / 批量 / fan-out；**大 diff 终审**（organizer 本体已持 context，交全新 `fable` subagent = 2x 费率 + rediscovery 双重惩罚 → 用 Opus + 对抗 fan-out 更划算）；**⑤ 也不是每次出计划都点**（例行 planning Opus 自己做，只在真拐点）。
- **频次闸**: Fable 5 是 session 内**个位数次**破玻璃；想点第 2 次先自检是不是 brief / 需求没框清（回去修 brief，不是升模型）。
- ⚖️ **防过省校准 (2026-07-06 Steve: "改用 fable 的时候就用 fable，不要太省")**: 频次闸是**上限不是配额** —— earned 车道清楚命中就**主动派**，别等 Steve 点、别为省周额度硬留 Opus。防滥用闸防的是**预测式**滥用（没观察到卡住就升 / 拿 model 轴掩盖 brief 没写清），**不是**压制 earned 命中。上闸（别预测式滥用）+ 下闸（别过省荒废）方向相反都要守；别把"想点第 2 次先自检"退化成"能不用就不用"。最常被漏派的 earned 命中 = **大批修复刚落地 → `fable` read-only diff-hunt**（同族前科预授权直通，见 [[feedback_fable_dont_be_too_stingy]] / [[feedback_fable_blocking_bug_hunt_from_diffs]]）。
- **经济学根据**: 设 Opus 轮=1、Fable 轮=2，p=Opus 1-2 轮解掉的概率 → earned 期望成本 ≈ p×1+(1-p)×4，p>~50% 时 earned 更省；本项目实证 base rate 高（绝大多数"看着难"的问题 Opus 一轮即倒）。预授权三类 = p 已知很低、或失败代价不在 token 维度。

详见 [`multi-model-dispatch.md`](./multi-model-dispatch.md) §Fable 5 定位铁律（含 worked examples）。

---

## 🔒 红线（执行者不许独立收尾 prod）

以下四类 brief 卡必须标 🔒，执行者只做到"实现 + 自测 + PR off origin/main"：

| 红线类别 | 事故证据 |
|---|---|
| **prod 部署 / DB migration / Flyway schema** | 5/30 RBAC ¥0（feature 分支并发部署覆盖）；Flyway 跨 session 撞号阻断所有人部署 |
| **权限 / RLS / 多租户 / 业态隔离** | 餐饮路由撞制造业工具瞎编；营收脱敏漏配（Jun 2 WS1 CRITICAL）|
| **架构 / 跨模块重构 / 新实体** | 执行者无全局视角，易造 schema drift / 循环依赖 / BaseEntity 遗漏 |
| **上线前 diff 终审** | 任何 merge/deploy 前最终审查 → Opus（可含对抗性多-agent）|

详见 [`multi-model-dispatch.md`](./multi-model-dispatch.md) 红线节。

---

## 隔离铁律（继承，brief 卡必体现）

- **每任务独立 worktree off `origin/main`**：`git worktree add -b feat/X ../cretas-X origin/main`
- **commit 锁 scope**：`git commit -m "..." -- F1 F2`（`--only` 模式）或 `./scripts/safe-commit.sh`
- **prod 永远从 main 部署**，绝不从 feature 分支部署
- **⛔ 禁 `mklink /J` 共享 node_modules**：Windows worktree remove 会把主 repo node_modules 掏空
- 每 PR 前：`git diff origin/main...HEAD --stat` 确认 scope 干净（无 sister 文件夹带）

详见 [`worktree-and-main-only-deploy.md`](./worktree-and-main-only-deploy.md) + [`concurrent-edit-safety.md`](./concurrent-edit-safety.md)。

---

## 审查分层

| 场景 | 审查者 |
|---|---|
| 例行 review（无风险改样式/lint/无红线依赖） | **Sonnet**（rule-aware in-harness，省 Opus 额度）|
| 🔒 Risky review（权限/迁移/业态/架构/上线终审） | **Opus**（organizer 本体，不可外包）|

---

## 闭环交接流程

```text
Steve 有想法
    ↓
Opus Organizer 接收（intake 前门）
    ├─ 需求框架：辨别真需求/假设，出 spec
    ├─ 任务拆解 + brief 卡写作
    ├─ scope 锁地图查重（台账 ACTIVE.md）
    └─ 决定 model × effort × orchestration（详见 multi-model-dispatch.md）
         ↓ in-harness                ↓ out-of-harness
    Sonnet subagent             Steve courier → Codex/Composer
    (规则自动可见)               (brief 卡必须自包含规则摘要)
         ↓                               ↓
    各自在独立 worktree off origin/main 实现 + 自测
         ↓
    PR → git diff origin/main...HEAD --stat 确认 scope 干净
         ↓
Opus Organizer 终审（🔒 risky 必经）→ merge 进 main
         ↓
Opus 从 main 部署 prod → 核对运行中 jar 含修复
（出货闸 —— 物理消除多 session last-write-wins）
```

---

## 速查判断树

```text
这个任务需要 Organizer 自己做吗？
  → 需求模糊高风险（防建错东西）: ✅ Organizer
  → 🔒 risky 终审 / prod 部署: ✅ Organizer（出货闸）
  → 难架构真有判断模糊: ✅ Organizer xhigh
  → 小而关键 keystone 已在 context: ✅ Organizer（brief+review 比直接做更贵？）
  → 其他（执行/批量/机械/常规 review）: 派 fleet

派给谁？（详表 → multi-model-dispatch.md）
  → 需要 .claude/rules 意识（Java Tool-Skill / Python parity / rule review）: Sonnet in-harness
  → 可完整 brief 的纯 UI / 样式 / lint: Composer
  → 可完整 brief 的 CLI / E2E / 构建 / TDD: Codex
  → 修 2 轮没好 / 改乱了: 停止，Opus root-cause

要不要升 Fable 5？（model 轴破玻璃顶层, 2x Opus, earned-not-predicted v2）
  → 预授权直通(可跳过 Opus)?
      → prod 事故计时中(客户被 block, 失败轮代价=小时): ✅ 直接派 `fable`
      → 同族前科(台账/memory 实证 Opus 打不动): ✅ 直接派 `fable`
      → 🔒 不可逆+小 diff 终审(迁移/RBAC/RLS/资金): ✅ 直接派 `fable`
  → 否则 Opus 先试 1 轮认真尝试:
      → 没收敛且能说清卡在哪: ✅ 升 `fable`(不撞第 2 轮; Opus 轮产物回收进 brief)
      → 难架构/模糊框架 Opus xhigh 已 wobble 且 stakes 高: ✅ 派 `fable` 单点
  → ⑤ 前瞻战略纠偏(投大 effort 前)?
      → 多线程程序将投大 effort 且(重心疑似飘/ROI 不确定/需 reconcile 历史), 或 Steve 点"审接下来怎么做": ✅ 派 `fable` read-only 战略 review(抓优先级反转+找回历史决策+剪 over-engineering)
      → ⛔ 例行出计划不点(Opus 自己规划), 只在真拐点
  → 其余 → Opus; ⛔ 不进执行/分诊/批量/fan-out/大 diff 终审; ⛔ body 不换 Fable 5
  → 频次闸: session 个位数次; 想点第 2 次 → 先疑 brief 没写清

Organizer 用多少 effort？
  → 日常: high（默认）
  → 单个难 turn: 该 prompt 加 ultrathink（只点一轮，最省）
  → 长自主 session / 真模糊架构: xhigh
  → max: ⛔ 破玻璃，几乎不用
  → ⚠️ 绝不常驻 max effort → 路由分诊用 high，把 xhigh/ultrathink 分配给真难的 turn
```
