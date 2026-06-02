# Cretas 意图分类器重设计 — 设计文档

**日期**: 2026-06-02
**状态**: 设计已定稿，待评审 → writing-plans
**核心理念**: 不追 SOTA 准确率，而是让**错配的代价趋近 0**（确定性正确 + 防呆 + 自学习 + 不可逆操作硬护栏是护城河）。

---

## 1. 背景与动机

意图识别是 Cretas（中文 ERP + 餐饮 BI 助手）的核心，承载 **510 个活跃意图**、读 + 写/破坏性操作混合。当前实现 `IntentRecognitionPipelineServiceImpl.java`（~4000 行）是多年累积的 ~19 个带版本号子策略（v33.1 / v11.2 / v14.0 / v32.2 / v11.4 / v7.1.1 / v12.9 …）。

经过两轮审计（一次代码勘探 + 一次 25-agent 对抗性二次审计）+ 外部研究 + 线上真库验证，确认了若干问题，也**纠正了第一轮的三个实质错误**。本设计基于校正后的事实。

---

## 2. 现状（审计校正后的事实）

| 维度 | 事实（已验证） |
|---|---|
| 意图规模 | **510 个活跃**（真库）。BERT 训练头只有 **179 类，覆盖 35%** → 严重过时 |
| 入口分叉 | `recognizeIntentWithConfidence`（走完整管线）vs `recognizeMultiIntent`（命中多意图触发词时直接调 `classifyMultiLabel`，**绕过整条管线**）→ 同句不同结果。已确认（HIGH 置信） |
| 短语层 | **~5,361 条**硬编码短语映射（不是 ~500），substring + 40% 覆盖 + longest-first，命中即 **0.93–0.98 短路返回，无语义验证**。部分路径误标 `MatchMethod.SEMANTIC` |
| BERT 分类器 | **不在路由热路径**——`classifierIntentMatcher.classify()` 唯一调用者是 `ShadowClassifyService`（@Async，纯数据采集）。`ai.use-python-matcher` 默认 false。是个"僵尸"：prod 白付 ~400MB 加载 + 30s 健康检查 + 一个误导的 "98%→92%" 告警，换零路由价值 |
| 写护栏 | **基本未建**。`needsApproval() = requires_approval AND sensitivity='CRITICAL'`。真库：72 个破坏性后缀意图仅 5 个 CRITICAL；**395/510（77%）required_roles 为空 → fail-OPEN RBAC**；`forceExecute=true`（多意图/对话续接）、动态规划、skill 路径都绕过确认门。**已做标签止血**（PR #418：9 个危险意图重标，11/11 CRITICAL 现要求审批），但 forceExecute bypass 仍在 |
| 校准 | **校准数据不存在**。Python `Calibrator(coefs={})` 是空壳，无 table、无 fitting job。纠正日志选择偏置（只在系统已答错时记） |
| 嵌入层 | gte-base-zh（2023，**768 维**不是 384）+ gRPC:9090 + pgvector(`ai_intent_configs.embedding`)。意图向量 = name+category+desc+keywords+examples（但 Java 内存 cache 只用 desc+keywords —— 不一致）。相似度直接当置信度，无映射。Java 阈值 0.72 / Python 短路 0.85 |
| 混淆对 | `findStrongPhraseCandidate` 已编码 **~25 个**读/写孪生对（CLOCK_IN/OUT、暂停批次、质检记录-vs-创建…）——不是 <6 |
| "死层"真相 | "19 层大多死"是**误诊**：大多是护栏（编码生产 bug 知识）。真死的只有 v4.0 并行打分（semanticFirst 默认 on 时不可达）。黑洞意图以**余弦 1.00** 匹配无关输入 → 单一阈值会让它**更糟** |

**头号风险**: 写护栏（W0）。真库证实危险写没被可靠拦住；这是整个重设计的承重不变量。

---

## 3. 锁定的决策

| # | 决策 | 选择 |
|---|---|---|
| 1 | 路由拓扑 | **Domain 预筛（~16 域）→ 域内细意图 kNN**（510 → 每域 ~15-25，阈值校准可行） |
| 2 | 嵌入模型 | **Qwen3-Embedding-0.6B + 指令前缀**（同体量，中文短查询提升；修 backfill/cache 取材不一致） |
| 3 | BERT | **W1 用 shadow 数据测量一次 → 几乎肯定退役**（收割其 OOD 设计进新路由；除非它能抓到嵌入路由漏的写误路由） |
| 4 | 写护栏 tier 来源 | **工具 action-type 为主**（自动推导，不被手维护的 sensitivity 列标错击败）；sensitivity 仅作额外升级信号 |
| 5 | abstain 阈值（默认，可调） | CONFIRM=0.70 / GAP=0.15 / abstain 率上限 20%；写意图偏向 clarify |

---

## 4. 目标架构

```
用户输入 (保留 raw originalInput)
  │
  ├─ [PRE-1] 共指/省略改写 (若有 sessionId) — 在任何 embedding 调用之前
  ├─ [PRE-2] 多意图触发检测 (在 RAW input 上) → 若命中: 抑制所有单答案快路径,
  │           走多标签分类, 传播 additionalIntents + executionStrategy
  │
TIER 1 — 确定性 (保留, 整合)
  • exact/hash 学习表 (Layer 1, 学到的表达 0-token 命中)
  • 短语层 (审计 5,361 条; 命中后做轻量验证, 不再盲目短路)
  • verb+noun 快路径 (带否定前缀守卫)
  • 黑洞 keyword 确认 + food-vs-data 双向消歧 在这里做硬前置过滤
  → 高置信路由直接出; 未确认的"被守卫意图"穿透 (不短路)
  │
TIER 2 — DOMAIN 预筛 → 校准嵌入 kNN (真正的"塌缩")
  • L1 粗域分类 (~16, 对齐工具目录) → L2 域内细意图 kNN
  • 合并 KEYWORD + SEMANTIC + CLASSIFIER + FUSION 为一个路由器
  • Qwen3-Embedding-0.6B, 查询与意图原型都加指令前缀
  • SEMANTIC_EXCLUDE 硬排除 (黑洞意图永不靠纯向量进候选) + SEMANTIC_GUARD 后置过滤
  • 读/写孪生 margin 守卫 (~25 对): top1−top2 < 0.08 在孪生对上 → abstain/LLM, 绝不自动走写侧
  • RAG 候选增强保留 (≥0.90 直接命中短路, ≥0.72 加权 boost, 带 provenance)
  • ABSTAIN (margin-based, 不依赖校准): top1<CONFIRM 或 top1−top2<GAP → 反问/列 top-2
  │
TIER 3 — 受约束 LLM over 检索 top-K (保留)
  • 候选表排除黑洞意图 (除非 keyword 确认)
  • 约束解码强制输出落在已知意图枚举内
  │
══════════ 与路由正交 ══════════
写护栏 (置信度无关, orchestrator 级单一 choke point)
  • tier 来自工具 action-type + blast-radius, 不是 sensitivity 列, 不是置信度
  • 任何 action-type ∈ {WRITE,UPDATE,DELETE} 或 sensitivity ∈ {HIGH,CRITICAL} → 强制 preview+confirm
  • forceExecute 可预填参数但不能跳过门; 动态规划/skill 路径全部经此门; RBAC 对写/删 fail-CLOSED
```

### 4.1 组件边界（每个单元一个清晰职责）

| 单元 | 职责 | 依赖 |
|---|---|---|
| `PreRewriter` | 共指/省略改写（sessionId + 6 轮窗 + 实体槽） | ConversationContext |
| `MultiIntentGate` | RAW input 多意图触发检测 + 抑制单答案路径 | 触发词表 |
| `DeterministicTier` | exact + 短语(带验证) + verb-noun + 黑洞/food-data 前置过滤 | 短语表、黑洞/食品常量 |
| `DomainPrefilter` | L1 粗域分类（~16 域） | 嵌入服务 |
| `EmbeddingRouter` | 域内 kNN + EXCLUDE/GUARD + 孪生 margin + RAG boost + abstain | 嵌入服务、pgvector、RAG |
| `ConstrainedLlmFallback` | top-K 上约束解码选意图 | LLM call_chain |
| `WriteGuard` | action-type → tier → preview/confirm/approval（置信度无关） | 工具元数据、TCC preview |
| `AbstainPresenter` | 列 top-2 中文名 + 写操作预览 + 选择回写自学习 | 自学习候选表 |

---

## 5. 不变量 / 回归安全清单（塌缩前必须保留）

二次审计的"考古"轨道确认：**每个版本号护栏都会被 naive 扁平塌缩 regress**。新架构必须显式保留：

| 护栏 | 真实修过的 bug | 新架构必须满足 |
|---|---|---|
| **黑洞语义守卫** (`SEMANTIC_EXCLUDE_INTENTS` / `SEMANTIC_GUARD_INTENTS`，标注 "4 wrong,0 correct"/"cosine 1.00") | 中心邻近的意图向量以余弦 1.00 匹配无关输入（"猪肉保质期"→ISAPI_QUERY，"PO-001"→FOOD_KNOWLEDGE）。单一阈值更糟 | EXCLUDE = 从候选集硬排除，只能经 keyword/phrase/LLM 到达；GUARD = 后置过滤，超阈值黑洞意图缺确认 keyword → 降级 rerank/abstain；所有 tier 都应用；保留二号候选提升（不返回 NO_MATCH）；常量集中为一处 |
| **食品知识 ↔ 工厂数据 双向消歧** (v16–v30) | "食品安全检查记录"误入 FOOD_KNOWLEDGE；"牛肉超标原因和预防"被挡在它之外 | 保留 3 条件检查（是 FOOD? + 有数据指示词? + 无知识问句 pattern?）；保留 v19/v21 覆盖优先级；保留 food-entity LLM 消歧 fallback（在 originalInput 上，LLM 不可用时 v16 无条件兜底）；3 个 token 集独立（不合并） |
| **多轮上下文 / 共指 / RAG** (v11.4) | 无状态路由解不出"那批货到了吗"/"它的温度呢"/"再查一下"；学过的正确短语停在中置信度总打 LLM | sessionId 一等公民；embedding 前先共指改写（实体槽 + 6 轮窗）；保留 lastIntentCode 连续性；RAG 直接命中(≥0.90)+加权 boost(≥0.72) 带 provenance；置信度下限单调 `max(rag,semantic)`；全部可选优雅降级 |
| **写操作置信度门 + 强短语豁免** (v7.1，0.12 gap 规则) | 写意图(CREATE/UPDATE/DELETE/CLOCK_OUT/PAUSE) 在 top1/top2 gap<0.12 时仅凭语义分派（"暂停"→错意图，"下班"→CLOCK_IN） | 打分后、任何 early-return 前的离散强制写门；`isWriteOperationType` 同时吃 ActionType 和 intentCode（后缀），无类型意图不当 query-safe；0.12 gap 仅对写意图；强短语豁免从属于写门且候选须已在打分列表内。**与写护栏(决策4)同一安全面** |
| **复合 / 多意图** (v12.x，verb-noun，two-stage) | 单最佳路由静默丢"查批次顺便看考勤"的第二意图；预处理 strip 触发词；"还有多少库存"误判多意图；否定动词("没创建")误触发 CREATE | RAW input 触发门预路由旁路；命中多意图时抑制所有单答案快路径；保留两处检测点；传播完整 additionalIntents + executionStrategy（bestMatch-only 返回会丢二级）；保留写 verb-noun 的否定前缀守卫；**补已知 gap**：裸"并"动词串联（"查原料并创建批次"）当前漏判 → 加"并"为触发词或句法处理 V1+并+V2；为 QUERY→CREATE 依赖链实现 SEQUENTIAL 策略 |

**原则**：塌缩 = **重新归位（re-home），不是删除**。只有 4 个打分层真合并 + v4.0 真删。所有护栏移植为显式 pre/post 过滤，并由 shadow 框架在删任何东西前验证零回归。

---

## 6. 写护栏设计（决策 4，W0 核心）

**模式**：Helmsman 三层，置信度无关，在 orchestrator（不在模型/路由/工具）：

| Tier | 操作类型 | 行为 |
|---|---|---|
| TIER_1 | 读 | 自动执行 + 审计 |
| TIER_2 | 可逆写 | 执行 + 异步审计 |
| TIER_3 | 不可逆 / 大爆炸半径 | 阻塞直到人工确认/审批 |

**关键实现要求**：
- Tier 由**工具 action-type + 可逆性 + 参数级 blast-radius（在 `doPreview` 估算）**决定，**绝不**由 sensitivity 列或置信度决定。
- 单一服务端 choke point，覆盖 **`IntentExecutionOrchestrator` + `ToolDispatchService` + `DynamicToolSelectionService` + `SkillExecutorImpl`**。
- `forceExecute=true` 可预填参数，但**不能跳过门**。
- RBAC 对 write/delete **fail-CLOSED**（required_roles 为空 → 拒绝，不是放行）。
- 每工具 `@RequiredPermissions`（最小权限）。
- 对抗性测试：必须验证多意图 / 对话续接 / 动态 / skill 四条路径都过门。

---

## 7. abstain UX 设计（决策 5）

- **触发**：`top1 < CONFIRM(0.70)` 或 `top1 − top2 < GAP(0.15)` → 反问。margin-based，**无校准依赖**。
- **展示**：top-2（不是 3），用**中文自然语言名**（不是 `RESTAURANT_REVENUE_QUERY` 这种码），含"都不是/重新输入"。
- **写意图**：选择前显示**每个选项会 DO 什么的预览**（对齐 `fool-proof-design.md` Rule 1）。
- **闭环**：用户的消歧选择 = 置信度 1.0 的标注样本 → upsert 进自学习候选表（镜像 PR #389 entity-resolution-history 模式），喂回 [[#406 近失捕获]]。

---

## 8. 分阶段计划（3 波）

### W0 — 分布无关安全（先做，可独立 ship）
1. 建真写护栏：单一 orchestrator choke point（覆盖 4 条执行路径）；fail-closed RBAC；forceExecute 不能跳过。**对抗性测试**。
2. 移植**全部 ~25** 混淆对为 canonical 集 + margin 守卫。
3. ship margin-based abstain + CRITICAL/HIGH 附近永远反问（无校准）。
4. 扩 golden 远超现有 ~50；**从代码里的 N-wrong/M-correct 注解构建回归集**（让生产 bug 知识变成测试）。
5. **核对线上 prod `ai_intent_configs`** 分布（已部分做：PR #418 标签止血）。
6. 移除误导的 "98%→92%" BERT 告警。

### W1 — 旁路建路由 + shadow
1. 在 monolith 旁立新 domain-prefilter + Qwen3-Embedding kNN 路由。
2. 把每个护栏（黑洞、food-vs-data、多意图、共指、RAG）重新归位为显式过滤，常量集中。
3. shadow 框架跑 ≥48h；对比表；在新分布上**用合成均衡 eval 集校准一次**（不用偏置纠正日志）。
4. **跑一次 BERT 测量**（shadow 行）；决定退役 vs 窄 tie-breaker；重新获取 D5 蒸馏信号。

### W2 — 塌缩 + BERT 退役 via canary
1. canary 5%→20%→50%→100%，门：sensitivity 分层 agreement（LOW/MED ≥95%，HIGH/CRITICAL ≥98%）+ **读↔写翻转=0** + tool-error 持平；qwen3-max 裁决分歧切片；auto-rollback。
2. 仅在零回归后移除 4 个合并打分层 + v4.0 死块 + BERT 模型加载。
3. 后续可选：加 Mahalanobis OOD + per-matcher 温度/conformal 校准，**只**给低风险意图减摩擦。

**承重不变量**：**W0 必须 ship 并验证后，才能开始 W2**。

---

## 9. Shadow → Canary 切换标准（无标签判"新≥旧"）

对比表字段：`{request_id, champion_label, challenger_label, champion_conf, challenger_conf, sensitivity_level, tool_error, tcc_aborted}`。

门指标：
- agreement_rate 按 sensitivity（LOW/MED ≥95%，HIGH/CRITICAL ≥98%）
- tool-error 持平
- **READ↔WRITE 翻转计数（任何正数都阻止晋升）**

分歧 ~5% 切片用 qwen3-max-as-judge（已在 call_chain）裁决。shadow ≥48h，canary 每阶段 ≥24h，auto-rollback on tool-error 2× baseline 或任何 WRITE flip。总 ~7-10 天。TCC 写门全程置信度无关。

注：shadow 能力部分已存在（`ShadowClassifyService`、`IntentMatchRecord`）但似休眠 → 先验证/复活，别假设可用。

---

## 10. 不做（YAGNI）

- 不追分类器 SOTA / 不重训大模型（bitter lesson 陷阱）
- 不 RAG 自己的 LLM 输出（回音室）
- 不一次性 big-bang（必须 shadow + canary）
- 不在塌缩里删任何护栏（只删 4 打分层 + v4.0）
- 不依赖当前不存在的校准数据（margin-first）

---

## 11. 风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| 写护栏建得不全（forceExecute/动态/skill 漏） | **高** | W0 单一 choke point + 4 路径对抗测试，W2 前必验 |
| 扁平路由在 510 意图退化 | 中 | domain 预筛（决策 1） |
| 塌缩 regress 护栏 | 中 | 回归安全清单 + shadow 零回归门 |
| 无校准数据 | 中 | abstain margin-first，校准后置可选 |
| 并发部署覆盖（Java 共享 jar） | 中 | 只从 main 部署 + 部后核对运行 jar（见 worktree-and-main-only-deploy 规则） |
| 嵌入迁移 cache/backfill 取材不一致 | 低 | W1 统一为 name+category+desc+keywords+examples |

---

## 12. 关联

- 二次审计 workflow（25 agent / 2.4M token）
- 止血 PR #418（intent sensitivity 重标）
- `fool-proof-design.md`（防呆 Rule 1 = abstain UX 基础）
- `worktree-and-main-only-deploy.md`（部署纪律）
- [[#406]] 近失候选捕获（喂 abstain 闭环）
- `ai-intent-tool-skill-architecture.md`（Tool-Skill 架构，写护栏挂在 ToolDispatch/SkillExecutor）
