# 自学习覆盖 路线图 / backlog

**最后更新**: 2026-06-02
**来源**: superpowers workflow 全系统覆盖审计 (44 agent) + 三个能力扩展的自学习接入分析。
**关联**: `2026-06-01-self-learning-coverage-v2-design.md` (v2 框架), memory `project_2026_06_01_self_learning_v2_build` / `feedback_smartbi_table_grant_gap`。

---

## 两个机制 (任何"学习点"先归类到其一)

| 机制 | 学什么 | 归宿 | 原则 |
|---|---|---|---|
| **毕业进规则** | 确定性 key→value 映射 (列名→标准字段 / 别名→实体 / 脏值→归一 / NL→意图) | `learning_promotion` 候选表 → 人审 `--apply` → committed JSON / 规则; 命中 0 token | 确定性、可证明正确、绝不自动毕业 |
| **蒸馏语料** | LLM 开放式推理/叙述 teacher pair | `distillation_capture` → `smart_bi_distillation_samples` → 够量 SFT | 只攒不训 (触发器命中才训); 绝不蒸馏自己的确定性输出 |

**铁律**: 确定性查询/映射 → 毕业(别 LLM); LLM 开放推理 → 蒸馏; codegen 绝不毕业; gold 确定性终态(如 8 销售问答)不该被"学习"。

---

## 已 LIVE (现状)

- **v1 field_mapping 闭环**: capture→promote(两层 行业分支→全局主干)→consult, 人审 --apply。
- **v2 泛化框架**: `learning_promotion.py` 支持任意 `learning_type` (field_mapping 已接; classification/data_cleaning 框架就绪但无 LLM 点 backlog)。
- **用户纠正 → 候选** (Task 6): `/auto-parse/feedback` mapping 纠正 method=user_correction conf=1.0。**后端就绪, 待前端纠正 UI 接通**。
- **蒸馏 capture**: `materialization` (物化洞察) + `chat_qa` (AI问答 general_analysis 非流) + `agent_insight` (经营驾驶舱 orchestrator)。真实场景实证 chat_qa + agent_insight 落库 (含真实用户行)。**未接**: chat SSE-stream。
- **实体解析 admin 确认毕业进 history** (#389): 人工确认 (raw_name→entity_id conf=1.0 decided_by_agent=admin) 写 `entity_resolution_history`, transitive 2-hop 下次 0 token 命中。**真实场景 E2E 实证** (青花椒徐汇光启城店: 别名"徐汇光启城店"确认后 transitive closure ships=TRUE conf=0.94)。grant fix #390 (整个实体解析写路径曾因缺 grant 死在 prod)。
- **Java 意图层已自学习**: LLM Reranking 主路径 `learnExpression → learned_expressions → 下次 Layer1 EXACT 0 token`。已有, 非遗漏。

---

## 三个能力扩展的自学习接入 (Steve 6.2 提的 + 另一 session 在建 feature)

> **先后顺序**: 这三个**首先是 feature 扩展** (追问/下钻/跨维分析功能本身)。自学习是**叠在上面的廉价捕获层** —— 功能一旦走对 LLM 路径, 蒸馏 hook 已就绪, 语料自动涨。不要为"能学习"硬加接线。

### 1. 更多问题 (覆盖率扩展)
- **机制**: 覆盖率学习 → 毕业新模板 (不是蒸馏, 不是答案毕业)。
- **信号源**: `smart_bi_llm_fallback_log` (流式问答已记 query + **embedding** + answer + 👍👎)。"所有模板都没命中→走 LLM 兜底"的问题就是覆盖缺口。
- **接入 (离线 job, 不阻塞功能)**: 按 embedding 聚类未覆盖问题 → 按频次排序 → top-N 人审 → 毕业成新模板/gold 问答。
- **答案归宿**: 新模板若 gold-backed (确定性) = 终态不蒸馏; 若仍需 LLM 答 = 进蒸馏。

### 2. 追问 / 下钻 (follow-up)
拆两类, 别混:
- **2a. 分析性解释** ("为什么涨"/"这意味着什么"/深度下钻分析): **LLM 推理 → 蒸馏**。
  - **接入**: 若走现有 `general_analysis` / insight 路径 → **已自动捕获** (chat_qa/agent_insight hook 已在)。若是新 drill-down LLM 端点 → 加一行 `persist_distillation_sample(source='drilldown', task_type='followup')` (共享 helper 现成)。
- **2b. 事实性元数据** ("这个字段是什么"/"这张图是什么类型"): **确定性查询 → 毕业进规则, 绝不 LLM**。
  - 字段含义来自 schema / 已毕业的 field_mapping (标准字段有 description); 图表语义来自 chart config。
  - **接入**: 直查 (0 token)。**反模式**: 把元数据问题喂 LLM = 浪费 token + 该毕业没毕业。审查现有下钻实现, 元数据类追问走 lookup 不走 LLM。

### 3. 跨维联系分析 ("VIP 跟菜品的联系")
- **机制**: 主要 **LLM 开放分析 → 蒸馏** (高价值垂直内容: 开放、prompt 多样、跨维推理)。次要: 反复出现的跨维 query pattern → 毕业成 cross-sheet 模板。
- **接入**: 跨维分析的 LLM 点加 `persist_distillation_sample(source='cross_dimension', task_type='analysis')`。若走 `cross_sheet_aggregator` / general_analysis → 多半已在捕获路径附近, 确认即可。

---

## coverage 审计的其余 backlog (按价值排)

| # | 项 | 机制 | 价值 | 工作量 | 状态 |
|---|---|---|---|---|---|
| ~~done~~ | 实体解析 admin 确认毕业 + grant fix | 毕业 | 高 | 小 | ✅ #389/#390 LIVE |
| A | 蒸馏增量: `insights/generator._generate_llm_insights` (6 caller) | 蒸馏 | 中 | 小 | 待 (与 materialization 同形, 增量多样性) |
| B | 蒸馏增量: `fallback_log → distillation_samples` ETL (流式问答语料导得出) | 蒸馏 | 中 | 小 | 待 (CRITICAL: ETL 过滤降级/截断答案, 只导 _llm_truncated=False) |
| C | 蒸馏增量: 餐饮菜谱 ai-draft 生成 (用 consume_auto_drafts 的采纳信号分级) | 蒸馏 | 中 | 小 | 待 |
| D | ArenaRL 锦标赛分支补 auto-learn (主路径已学, 仅此窄分支漏) | 毕业 | 低 | 5 行 | 待 (一致性修) |
| E | ToolRouter LLM 工具选择 (query→tool) 毕业 | 毕业 | 低 | 中 | 暂缓 (需先加成功门控; <5% 尾路径) |

> **蒸馏增量 (A/B/C) 不急**: 当前样本仅 ~25-30, 远没到 ≥1000/业态 SFT 阈值。逼近阈值前做即可。

---

## 不值得 / 不该学 (审计确认)

- **data_cleaner LLM-codegen 清洗**: 铁律"LLM 生成代码绝不毕业"; sandbox 不 auto-apply, 安全。归 backlog 人审, 非自学习范围。
- **food_kb 反馈**: 前端只写 localStorage, 后端无消费者, 死端; 外围低用量。RAG accept/reject 是 eval 信号/蒸馏负样本, 非可毕业映射。
- **restaurant review 分析**: 真 LLM 结构化抽取归宿是蒸馏, 但 prod 0 行 (客户没传评论数据)。dormant hook, 待数据。
- **8 个 gold 销售物化问答**: 确定性终态, 本就是规则正确答案, **不该被学习** (蒸馏它们=训概率模型近似确定性正确系统=倒退)。
- **元数据查询** (字段/图表含义): 确定性, 该直查毕业, 不该 LLM 也不该蒸馏 (见 2b)。

---

## 反复踩的硬教训 (做任何接入前看)

1. **smartbi 新表必须 GRANT DML 给 smartbi_user** (`feedback_smartbi_table_grant_gap`): 否则只默认 SELECT, 写 permission denied 被 fail-open 吞→静默 0 行。已复发 2 次 (#367 candidate / #390 entity_resolution 整个写路径死)。新表迁移必带 GRANT DML+sequence。
2. **写功能必须真库 E2E**, mock 单测永远绿。"部署成功但表 0 行/no exception 没写进"第一嫌疑 grant gap。
3. **workflow/agent grep 的是工作目录的分支, 不是 origin/main** —— 主工作目录常在 stale feature 分支, 会误报"代码不存在"。审代码前确认在 main。
4. **fail-open capture**: 学习捕获绝不阻塞主功能 (确认/上传/问答), 但必配可观测 (否则 silent death)。
