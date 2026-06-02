# P2 — 综合分析 (Multi-Dim Synthesis) 设计 spec

**日期**: 2026-06-02
**作者**: 资深架构师 (workflow 产出)
**业态**: 餐饮 (qhj = `RES_3101_009` 青花椒连锁, 28 门店 / 2 城市)
**依赖**: P1 评价工具 (`review_queries.py` + `/api/smartbi/review-*` 6 聚合端点 + 意图绑定) 必须先 ship
**关联 rules**: `.claude/rules/fool-proof-design.md` (诚实标注/next-action), `.claude/rules/python-java-port.md` (本模板用 `float()` 即可, 非 byte-strict), `feedback_intent_gate_must_cover_all_execution_paths`, `feedback_rules_first_llm_fallback`

---

## 1. 问题陈述

### 1.1 现状 (已查证)

| 入口 | 现状 | 局限 |
|---|---|---|
| `AgentOrchestrator.answer_insight` (`smartbi/agent/orchestrator.py`) | 经营驾驶舱 AI 洞察唯一入口. 确定性拉 3 个 gold 查询 (`finance_summary` / `top_products` / `discount_breakdown`) → 拼成 prompt → LLM 300 字叙述. **不出图**, **只覆盖财务/销售, 不接评价**. | 单一财务视角; 无评价/口碑维度; 无图; 无 fact-check 对账 (靠 prompt clause 防幻觉, 无机器校验) |
| `InsightGenerator` (`services/insights/generator.py`) | 单数据集 (df) → tier 化 prompt → LLM insights. 走 `chat.py /general-analysis` + `/general-analysis-stream`. | **单指标/单数据源**; 不跨 (评价 + 销售 + 财务) 多源合成 |
| `InsightDimensionAnalyzer` (`services/insight_dimensions.py`) | 4 支柱框架 (descriptive/diagnostic/predictive/prescriptive) + KPI 基准库 + 相关性/异常/贡献分析. **已 import 进 chat.py 但只被 drill-down/root-cause 间接用**, 多源综合从未调用. | 框架完备但 **无人把多个确定性指标喂给它做多维合成** |
| `llm_guard.py` | `detect_numeric_hallucination()` (检测 `亿/千万` 超 agg 上限) + 3 个 guard clause (NUMERIC/LABELING/ACTION_REC). | **只检测幻觉, 不与确定性指标逐项对账** (无 FactBook reconciler) |

**痛点**: 用户问 "综合分析青花椒的评价和经营" / "VIP 和菜品、门店的关系" 这类**自由、跨维度**问题时:
- `general-analysis-stream` 会路由到单数据集模板或单指标 LLM, 拿不到评价 + 财务 + 销售的合并视角;
- 经营驾驶舱 orchestrator 只看财务, 完全不知道评价数据;
- 输出无图, 数字防幻觉只靠 prompt (软约束).

### 1.2 目标

为 P2「综合分析」建一个 **多源合成引擎**, 把自由的"综合/多维"问题路由进来:

1. **聚合多个确定性指标**: P1 评价工具 (6 聚合) + gold 财务 (`finance_summary`) + gold 销售 (`top_products` / `channel_breakdown` / `discount_breakdown`) → 拼成一个 **FactBook** (确定性数字字典).
2. **合成层** 把 FactBook 喂给 `InsightDimensionAnalyzer` (结构化多维洞察) + LLM (grounded 自然语言归因 + 4 要素建议).
3. **fact-check**: LLM 输出与 FactBook 逐项对账, 数字不得编造 (扩展 `llm_guard` 为 `FactReconciler`).
4. **出多张图**: 复用各确定性指标已产出的 `chart_config` (评价星级 pie / 门店营收 bar / 渠道 pie / VIP 对比 bar / 时段折线).
5. **诚实标注**: 菜品标签注明"口味/品质标签非菜名"; 小样本注明 (差评 396 / VIP / 投诉申诉); 空数据给 next-action 不 dead-end.

**复用现有框架不重写**: `InsightDimensionAnalyzer` + `llm_guard` + gold queries + P1 review queries + `call_chain` (SLOT.INSIGHTS).

---

## 2. 真值锚点 (已查证 prod, RES_3101_009)

合成层产出的任何数字都必须能在下表 (FactBook) 中找到. fact-check 拿这些做基准.

### 2.1 评价 (`smart_bi_dynamic_data.row_data` jsonb, 按 `评价ID` DISTINCT 去重 72438 raw → **19845 unique**)

| 维度 | 真值 |
|---|---|
| 平均分 | 星级 **4.79** / 服务 4.80 / 环境 4.79 / 口味 4.79 |
| 好评 (≥4.5 星) | 18139 |
| 差评 (≤3 星) | **396 (小样本, 必注明)** |
| VIP | 是 2485 (avg 4.50) / 否 17360 (avg 4.83) |
| 平台 | 点评 19189 (4.80) / 美团 656 (4.57) |
| 回复 | 已回复 19452 / 未回复 393 → 回复率 **98%** |
| 时段 (`time_period` datetime, ~73% 有值, 5420 空) | 午 11-14: 5989 (4.85) / 晚 17-21: 6810 (4.82) / 下午 15-16: 625 (4.58) / 早 5-10: 522 (4.44) / 夜 22-4: 479 (4.31) |
| 好评高频菜品标签 | 味道好 5998 / 实惠 1791 / 鲜嫩 1394 / 新鲜 1295 / 香辣 1046 |
| 差评高频 | 味道差 79 / 份量太小 54 / 不实惠 39 / 不新鲜 24 |
| 差评最多门店 | 鲜行者X顺德小馆 (虹口龙之梦店) 64 条 |
| 城市 | 上海 4.790 / 杭州 4.776 |

**诚实标注铁律**:
- `菜品标签` = **口味/品质标签** (鲜美/太软了/味道好), **非菜名**. 合成层 prompt + 输出文案都必须标 "高频好评词 (口味/品质标签, 非菜名)".
- `投诉类型` = **商家申诉, 小样本** (不可当客诉量).
- 差评 396 条相对 19845 是 **小样本 (~2%)**, 任何"差评高频"结论必带样本量注明.

### 2.2 销售 / 财务 (gold `agg_daily` / `agg_product_daily`, 经 `finance_summary` 等)

- 总营业额 / 订单数 / 客单价 / Top 门店 / Top 菜品 / 渠道占比 / 折扣结构 — 由 gold queries 实时算 (单一真值, RBAC 转发后金额非零, 见 `feedback_java_python_rbac_role_forward`).

### 2.3 row 可用字段 (评价 jsonb)

`评价ID, 星级分, 服务分, 环境分, 口味分, 是否vip(是/否), 平台, 回复状态(已回复/未回复), 服务标签, 环境标签, 菜品标签(口味词逗号分隔非菜名!), 城市, 省份, 评价门店, time_period(评价datetime), 投诉类型, 评价质量, 用户等级, 用户昵称, 评价详情`

---

## 3. 架构总览

```
用户自由问题 ("综合分析青花椒的评价和经营" / "VIP 和菜品门店的关系")
  │
  ├─[Java 意图层] COMPREHENSIVE_SYNTHESIS 意图 (新) → 绑定 Java tool → GoldFinanceClient (转发 X-User-Role)
  │        或 [Python chat.py /general-analysis-stream] 综合分析路由器 (短语/语义判别)
  ▼
ComprehensiveSynthesisEngine (新: smartbi/agent/synthesis_engine.py)
  │
  ├─1. 维度规划 (plan_dimensions): 从问题抽 {要不要评价? 要不要财务? 要不要销售? 关注哪个交叉?}
  │      规则优先 (短语命中) → 不确定才 LLM 1 句分类 (per feedback_rules_first_llm_fallback)
  │
  ├─2. 并行拉确定性指标 (asyncio.gather, 复用现成):
  │      P1: review_overview / review_by_vip / review_by_platform / review_by_time / review_dish_tags / review_worst_stores
  │      gold: finance_summary / top_products / channel_breakdown / discount_breakdown
  │      → 组装 FactBook (确定性数字字典, 见 §4.2)
  │
  ├─3. 结构化多维洞察: InsightDimensionAnalyzer.analyze(df_facts, focus_dimensions)
  │      (descriptive / diagnostic[相关性: VIP×星级, 时段×营收] / prescriptive)
  │      → InsightReport (结构化 insights + risk + opportunity)
  │
  ├─4. LLM grounded 叙述: call_chain(SLOT.INSIGHTS, prompt含FactBook+InsightReport摘要)
  │      system prompt = orchestrator SYSTEM_PROMPT + llm_guard 3 clause + 诚实标注 clause
  │
  ├─5. fact-check: FactReconciler.reconcile(llm_text, FactBook)
  │      ├─ 哨兵: LLM 提的数字不在 FactBook 容差内 → 标注/降 confidence
  │      └─ detect_numeric_hallucination (亿/千万 上限) — 复用现成
  │
  ├─6. 出图: 收集各确定性指标的 chart_config (评价 pie / 门店 bar / 渠道 pie / VIP bar / 时段 line)
  │
  ▼
ComprehensiveSynthesisResponse { answer(markdown), charts[], factbook, insight_report, source, tokens }
  → SSE 流式 (meta / delta / charts / done) 复用 general-analysis-stream 事件格式
```

**关键设计决策**:
- **不重写**: `InsightDimensionAnalyzer` 做结构化多维 (相关/异常/贡献), LLM 只做自然语言归因 + 建议. 二者互补.
- **确定性优先**: 所有数字来自 gold/P1 SQL 聚合, LLM 永远拿到真实数字 (orchestrator 同设计). LLM 不算数, 只解读.
- **grounding 双层**: prompt clause (软) + `FactReconciler` 机器对账 (硬). 后者扩展 `llm_guard`, 不另起炉灶.
- **脱敏继承**: P2 LLM 调用全走 `call_chain` (SLOT.INSIGHTS) → 共享 `_RedactingLLMClient` 包装层 (main 上 PR #335/#355). 部署从 main → P2 自动继承出境脱敏, **无需在 P2 重做** (否则 double-redact, 见 python-java-port 架构注释). 但必须像 orchestrator 一样 **设 RedactionScope / `_llm_factory`** 让 egress 按租户归因 (见 §5.4 风险).

---

## 4. 详细设计

### 4.1 入口路由 (如何判别"综合/多维"问题)

两条路径并存 (与现有 SmartBI 路由一致):

#### 路径 A — Java 意图层 (经营驾驶舱 / AIChat 快捷问)

新增意图 `COMPREHENSIVE_SYNTHESIS` (business_type=RESTAURANT, priority 高于单维评价/财务意图但低于精确短语), 绑定 Java tool (复用 `GoldBackedRestaurantTool` 基类模式, 见 Jun 2 memory) → `GoldFinanceClient.fetchComprehensiveSynthesis(...)` (转发 `X-User-Role`) → Python `/api/smartbi/synthesis/comprehensive`.

绑定关键词 (迁移 INSERT): `综合分析`, `整体分析`, `经营和评价`, `评价和经营`, `多维分析`, `VIP和菜品`, `客群和门店`, `关系分析`, `综合诊断`, `全面分析`.

#### 路径 B — Python chat.py 路由器 (自由问题 fallthrough)

在 `general-analysis-stream` 的路由链中, **早于** 单数据集 template 路由插入 `match_comprehensive_synthesis(query)` (复用 `restaurant_ops_router` 的短语-pattern 模式, 见 `gold/restaurant_ops_router.py:148`):

```python
# smartbi/agent/synthesis_router.py (新, 镜像 restaurant_ops_router 结构)
_SYNTHESIS_PATTERNS: List[List[str]] = [
    ["综合", "分析"], ["整体", "分析"], ["综合", "诊断"], ["全面", "分析"],
    ["评价", "经营"], ["经营", "评价"], ["多维"],
    # 交叉关系: 需 ≥2 维度实体同现
    ["VIP", "菜品"], ["VIP", "门店"], ["客群", "门店"], ["时段", "营收"],
    ["平台", "评分"], ["城市", "营收"],
]

def match_comprehensive_synthesis(query: str) -> bool:
    """规则优先判别. 命中任一组(组内全部 token 命中) → True.
    不命中 → False (交给后续单维路由 / LLM). per feedback_rules_first_llm_fallback:
    规则只在确信时出手, 不确定不强占."""
    q = query.lower()
    for group in _SYNTHESIS_PATTERNS:
        if all(tok.lower() in q for tok in group):
            return True
    return False
```

判别原则 (per `feedback_rules_first_llm_fallback`):
- **规则确信命中** → 进综合引擎.
- **不命中** → 不强占 (交给单维评价/财务/template 路由). 综合引擎是 **附加路径, 不抢现有单维准确路由**.
- 不在路由层调 LLM 判别 (省 token + 避免误路由). 维度规划 (§4.3 step1) 内部才用 LLM 兜底, 且只对"已确定是综合问题"的 query.

⚠️ **路由覆盖铁律** (per `feedback_intent_gate_must_cover_all_execution_paths`): 综合引擎入口必须同时接 **路径 A (Java tool)** 和 **路径 B (Python stream)** 两条; 否则一条路命中一条路漏 → 同问题不同入口结果不一致. Task 中明确两路都测.

### 4.2 FactBook 设计 (确定性数字字典)

合成层的"事实底座". 所有数字唯一真值来源. 结构:

```python
# smartbi/agent/factbook.py (新)
@dataclass
class FactBook:
    """确定性指标汇总. LLM/InsightDimensionAnalyzer 都从这里读数, 永不自己算.
    每个 fact 带 label (口径) + sample_size (样本量) + basis (基准)."""
    review: Optional[Dict[str, Any]] = None      # P1 review_overview/by_vip/...
    finance: Optional[Dict[str, Any]] = None      # gold finance_summary
    sales: Optional[Dict[str, Any]] = None        # gold top_products/channel/discount
    cross_hints: List[Dict[str, Any]] = field(default_factory=list)  # 交叉关系 (VIP×星级 等)
    notes: List[str] = field(default_factory=list)  # 诚实标注 (小样本/标签语义/空数据)

    def to_prompt_lines(self) -> List[str]:
        """渲染为可读 prompt 文本 (镜像 orchestrator._build_user_prompt 风格,
        不用 JSON dump). 每个数字紧跟口径标注 (LABELING_GUARD 要求)."""
        ...

    def to_facts_index(self) -> Dict[str, float]:
        """扁平化为 {fact_name: value} 供 FactReconciler 对账."""
        ...
```

**FactBook 渲染示例** (诚实标注内联):

```
## 评价 (共 19,845 条去重评价, 按 评价ID DISTINCT)
- 平均星级 4.79 / 服务 4.80 / 环境 4.79 / 口味 4.79
- 好评(≥4.5星) 18,139 条; 差评(≤3星) 396 条 [小样本, 占比约 2%, 结论需谨慎]
- VIP 评价 2,485 条(平均 4.50 星) vs 非 VIP 17,360 条(平均 4.83 星)
  [注意: VIP 评分反而更低, 是真实信号]
- 高频好评词 [口味/品质标签, 非菜名]: 味道好 5,998 / 实惠 1,791 / 鲜嫩 1,394
- 高频差评词 [口味/品质标签, 非菜名]: 味道差 79 / 份量太小 54 [基于 396 条差评小样本]
## 经营 (gold agg_daily, 区间 {start}~{end})
- 总营业额 ¥{revenue}[毛/应收]; 订单数 {bills}; 客单价 ¥{avg_bill}[毛]
- Top 3 门店(按营业额): ...
## 诚实标注
- 菜品标签实为口味/品质标签, 不代表菜名销量
- 投诉类型为商家申诉口径, 样本小, 不等于客诉总量
```

### 4.3 合成层设计

```python
# smartbi/agent/synthesis_engine.py (新)
class ComprehensiveSynthesisEngine:
    def __init__(self, pool, *, budget_tracker=None, cache=None):
        self._pool = pool
        self._budget = budget_tracker or AgentBudgetTracker(pool)   # 复用
        self._cache = cache or NarrativeCacheService(pool)          # 复用 (narrative_cache)
        self._dim_analyzer = InsightDimensionAnalyzer()             # 复用
        self._reconciler = FactReconciler()                        # 新 (§4.4)

    async def synthesize(self, factory_id, question, date_range) -> SynthesisResponse:
        # step 0: cache check (narrative_cache, q_hash 含 question+range+factory)
        # step 0.5: budget check (复用 orchestrator 同机制)

        # step 1: 维度规划 (规则优先, 不确定才 LLM 1 句)
        plan = self._plan_dimensions(question)   # {review:bool, finance:bool, sales:bool, cross:[...]}

        # step 2: 并行拉确定性指标
        factbook = await self._build_factbook(factory_id, date_range, plan)

        # step 3: 结构化多维 (相关性/贡献) — 把 factbook 摊成 df 喂 analyzer
        insight_report = self._dim_analyzer.analyze(
            df=factbook_to_dataframe(factbook),
            context={"scope": "comprehensive", "period": f"{start}~{end}"},
            focus_dimensions=[WHAT_HAPPENED, WHY_HAPPENED, RECOMMENDATION],
        )

        # step 4: LLM grounded 叙述
        prompt = self._build_prompt(question, factbook, insight_report)
        answer, tokens = await self._call_llm(prompt)   # call_chain(SLOT.INSIGHTS)

        # step 5: fact-check (硬对账)
        answer, fc_meta = self._reconciler.reconcile(answer, factbook)

        # step 6: 收集 charts
        charts = self._collect_charts(factbook, plan)

        # bookkeeping: budget.consume + cache.put
        return SynthesisResponse(answer, charts, factbook, insight_report, ...)
```

**喂 LLM 的方式** (防编造):
1. prompt 只含 **FactBook 渲染文本** (真实数字) + **InsightReport 摘要** (结构化结论, 已是确定性算出的相关性/贡献). LLM 看不到原始 19845 行, 只看聚合数字 → 无法编造门店/数字.
2. system prompt 拼接: orchestrator `SYSTEM_PROMPT` (餐饮分析师 + 4 要素建议) + `NUMERIC_GUARD_CLAUSE` + `LABELING_GUARD_CLAUSE` + `ACTION_REC_GUARD_CLAUSE` + **新 `HONEST_LABEL_CLAUSE`** (菜品标签非菜名/小样本/空数据 next-action).
3. `temperature=0.3`, `max_tokens=900` (比 orchestrator 600 略大, 因多维; 仍受 budget gate).

`_plan_dimensions` (规则优先, per `feedback_rules_first_llm_fallback`):
```python
def _plan_dimensions(self, q: str) -> Dict[str, Any]:
    ql = q.lower()
    plan = {"review": False, "finance": False, "sales": False, "cross": []}
    if any(k in ql for k in ("评价","口碑","星级","好评","差评","vip","投诉")): plan["review"]=True
    if any(k in ql for k in ("营收","营业额","经营","财务","客单价","收入")): plan["finance"]=True
    if any(k in ql for k in ("菜品","商品","销量","畅销","渠道","折扣")): plan["sales"]=True
    # 交叉关系
    if "vip" in ql and any(k in ql for k in ("菜品","门店","评价")): plan["cross"].append("vip_x_rating")
    if "时段" in ql and any(k in ql for k in ("营收","营业")): plan["cross"].append("time_x_revenue")
    # 兜底: "综合/整体/全面" 但没点明维度 → 全开 (默认评价+财务+销售)
    if not (plan["review"] or plan["finance"] or plan["sales"]):
        plan["review"]=plan["finance"]=plan["sales"]=True
    return plan
    # 注: 此处不调 LLM. 规则覆盖足够 (综合问题维度词有限).
    #     若未来出现规则覆盖不到的 query, 再在此加 LLM 1-句分类兜底.
```

### 4.4 FactReconciler (grounding 机器对账 — 扩展 llm_guard)

不另起炉灶, 在 `llm_guard.py` 同模块新增 `FactReconciler` (或 `smartbi/services/fact_reconciler.py`, 复用 `extract_max_agg_value` / `detect_numeric_hallucination`):

```python
# 扩展 smartbi/services/llm_guard.py
class FactReconciler:
    """LLM 输出与 FactBook 逐项对账. 安全设计: 只对'指标名紧跟数字'的明确引用
    做对账, 宁漏不错 (不强行纠正模糊表述). per memory PR #337/#338 grounding."""

    def reconcile(self, answer: str, factbook: FactBook, *, tol: float = 0.05) -> Tuple[str, Dict]:
        facts = factbook.to_facts_index()   # {fact_name: true_value}
        # 1. 哨兵: 扫 answer 中 "{已知指标名} ... {数字}" 模式, 数字偏离真值 > tol → 标注
        #    例: answer 说 "平均星级 4.5" 但 factbook 是 4.79 → 加注 "(实际 4.79)" + 降 confidence
        # 2. 复用 detect_numeric_hallucination (亿/千万 超上限)
        # 3. 哨兵: answer 编造 factbook 没有的门店名/菜名 → 检测 (门店名白名单来自 factbook.top_stores)
        # 4. 万/亿单位归一 (per memory 阶段2 grounding)
        # 返回 (可能加注的 answer, {violations:[...], reconciled:bool, confidence_adj:float})
```

对账原则 (per `feedback_rules_first_llm_fallback` + 阶段2 grounding 历史):
- **只精确匹配指标名 + 紧跟数字**, 宁漏不错 (不模糊纠正).
- 无 matching fact → no-op (不冤枉 LLM).
- 检测到编造门店/菜名 (不在 FactBook 实体集) → 标注 "[未在数据中找到该名称]" + 降 confidence.
- 偏差 > 5% → 用 FactBook 真值回填 + 加注.

### 4.5 出图设计

收集各确定性指标已产出的 `chart_config` (复用, 不新做图):

| 维度 | 图 | 来源 |
|---|---|---|
| 评价星级分布 | pie | P1 `review_overview` chart_config (镜像 `reviews_sentiment_summary.py:391` 星级 pie) |
| 门店营收排行 | bar | gold `finance_summary.top_stores` → 组装 bar (镜像 `RestaurantGoldGrid` 营收排行) |
| 渠道占比 | pie | gold `channel_breakdown` |
| VIP vs 非VIP 评分 | bar | P1 `review_by_vip` → 组装 bar (2 柱) |
| 时段评分/营收 | line | P1 `review_by_time` → 组装 line |

图按 `plan` 维度门控 (只出问题相关的图). chart_config 格式 = ECharts option (`{type, title, ...series}`), SSE `charts` 事件下发. 前端 AIQuery `renderChartFromConfig` 已支持 (per Jun 2 memory)。

每张图 emit 时附 `title` 含口径 + 诚实标注 (如 "VIP vs 非VIP 平均星级 (VIP 反低, 真实信号)").

### 4.6 与 P1 工具的依赖

P2 **强依赖 P1**. P1 必须先 ship 以下 (per Jun 2 memory `review_queries.py` 设计):

| P1 提供 | P2 消费 |
|---|---|
| `review_overview(pool, factory_id, date_range)` → 平均分/好评差评/总数 + 星级 pie chart_config | FactBook.review + 评价星级图 |
| `review_by_vip(...)` → VIP/非VIP 分组 avg | FactBook.cross (vip_x_rating) + VIP bar |
| `review_by_platform(...)` | FactBook.review.by_platform + 平台图 |
| `review_by_time(...)` → 时段分组 (含 5420 空值处理) | FactBook.cross (time_x_revenue) + 时段 line |
| `review_dish_tags(...)` → 好评/差评高频标签 (已标"口味标签非菜名") | FactBook.review.dish_tags (诚实标注继承) |
| `review_worst_stores(...)` → 差评最多门店 (小样本注明) | FactBook.review.worst_stores |

**P1 缺失时的降级**: 若 P1 review 端点未部署 / 评价数据为空, FactBook.review = None, plan["review"] 强制 False, 合成只用财务+销售 + 在 answer 头部加 next-action: "评价数据暂未接入, 当前基于经营数据综合. 上传大众点评/美团评价导出后可补充口碑维度". (per fool-proof Rule 5, 不 dead-end).

P2 的 review 调用应直接复用 P1 的 query 函数 (import `from smartbi.agent.review_queries import review_overview, ...`), **不重复写 SQL** (避免 evaluate ID 去重逻辑两处漂移).

---

## 5. 风险

(放进结构化 risks 字段; 此处展开)

### 5.1 路由误抢 (单维问题被综合引擎吞掉)
"评价怎么样" (纯评价单维) 不应进综合引擎, 否则跑全量多源拖慢 + 答非所问. 缓解: `match_comprehensive_synthesis` 要求**多维度词同现** (评价+经营 / VIP+菜品), 单一维度词不命中; 综合引擎是附加路径不抢单维准确路由 (per `feedback_intent_gate_must_cover_all_execution_paths` 要测 8+ 边界 query: 既测综合问命中, 也测单维问 NOT 命中).

### 5.2 LLM 编造交叉关系 (相关 ≠ 因果)
"VIP 反而评分低 (4.50 vs 4.83)" 是真实信号, 但 LLM 可能编造因果归因 ("因为 VIP 期望高"). 缓解: InsightDimensionAnalyzer 只给相关系数 (descriptive), prompt 明确 "只陈述数据关系, 因果推断需标注'推测'"; FactReconciler 检测编造数字. 小样本交叉 (差评 396 拆 VIP) 必带样本量警告.

### 5.3 多源拉数延迟 (gold + P1 评价聚合 N 次 SQL)
评价 19845 行 jsonb 聚合 + 多个 gold 查询串行会慢. 缓解: `asyncio.gather` 并行 (orchestrator `_gather_data` 同模式, 各打不同表无争用); narrative_cache 命中直接返 (q_hash 含 question+range); P1 评价聚合若慢需 P1 侧加物化/索引 (P2 不负责).

### 5.4 脱敏 scope 未设 (出境真店名/菜名)
P2 走 call_chain 自动经 `_RedactingLLMClient`, 但**必须像 orchestrator (PR #355) 一样设 RedactionScope + `_llm_factory`**, 否则脱敏不 fire (egress `sanitized=false`) → 真店名/菜名直发 DashScope. 缓解: synthesis_engine `_call_llm` 前设 scope (敏感列 = 门店名/菜名/用户昵称); 部署后查 `smart_bi_llm_egress_audit` 确认 `sanitized=t` (per memory P0 redaction 教训). **这是 P0 数据主权要求, 不可漏**.

### 5.5 FactReconciler 误纠正 (把对的标成错)
对账 tol 太严 / 指标名模糊匹配 → 把 LLM 正确数字误标错. 缓解: 只精确匹配"指标名+紧跟数字", 宁漏不错; tol=5%; 无 matching fact → no-op (per python-java-port Rule 思路 + 阶段2 grounding 安全设计).

### 5.6 诚实标注被 LLM 丢弃
prompt 给了"菜品标签非菜名"但 LLM 输出仍说"招牌菜". 缓解: HONEST_LABEL_CLAUSE 强约束 + FactReconciler 检测"菜名"措辞出现在 dish_tag 上下文时加注; 物化/单测断言输出含"口味标签"字样.

### 5.7 P1 未 ship / 评价空数据 dead-end
P2 依赖 P1. 缓解: §4.6 降级 — review=None 时只用经营数据 + next-action 提示 (fool-proof Rule 5), 不报错不空白.

### 5.8 缓存串味 (综合答案 cache 命中错维度)
narrative_cache q_hash 若不含 plan 维度, 不同维度问题可能命中同 cache. 缓解: q_hash 含完整 question 原文 (维度规划是 question 的确定性函数, 同 question → 同 plan → 同答案, 安全).

### 5.9 byte-shape / Decimal (低风险)
本模板用 `float()` 即可 (per python-java-port: 本模板非 byte-strict, dict-eq 足够). 金额渲染复用 orchestrator `_fmt_money`. 无 Java parity 约束.

---

## 6. 实施任务 (bite-sized, TDD, subagent-driven)

> 前置: **P1 评价工具必须先 merge** (`review_queries.py` + `/review-*` + 意图). P2 Task 顺序假设 P1 已在.
> 每个 Task 先写 test (TDD), 真实 PG E2E 验证 (per memory: 单测/H2 假绿, 真库才暴 bug).

### Task 1 — FactBook 数据结构 + 渲染 (`smartbi/agent/factbook.py`)
- `FactBook` dataclass + `to_prompt_lines()` (诚实标注内联) + `to_facts_index()` + `factbook_to_dataframe()`.
- Test: 给定 review/finance/sales 三块 dict → 渲染文本含口径标注 ([毛]/[口味标签非菜名]/小样本); facts_index 扁平正确; 空 review → review 段省略 + notes 含 next-action.
- 不调外部, 纯函数, 快.

### Task 2 — 综合路由器 (`smartbi/agent/synthesis_router.py`)
- `match_comprehensive_synthesis(query) -> bool` (镜像 restaurant_ops_router 短语 pattern).
- Test (边界铁律): "综合分析评价和经营"→True; "VIP和菜品关系"→True; "评价怎么样"→False; "总营收多少"→False; "畅销菜品"→False; 8+ case 覆盖命中 + NOT 命中.

### Task 3 — FactReconciler (扩展 `smartbi/services/llm_guard.py`)
- `FactReconciler.reconcile(answer, factbook, tol=0.05)` — 复用 `extract_max_agg_value`/`detect_numeric_hallucination`; 哨兵对账 + 编造门店名检测 + 万/亿归一.
- Test: LLM 说 "平均星级 4.5" / factbook 4.79 → 加注 "(实际 4.79)" + 降 confidence; LLM 编造 "招牌牛肉店" 不在 top_stores → 标注; 正确数字 → no-op; 无 matching fact → no-op (宁漏不错).

### Task 4 — 维度规划 + FactBook 组装 (`synthesis_engine.py` 上半)
- `ComprehensiveSynthesisEngine._plan_dimensions(q)` (规则) + `_build_factbook(factory_id, range, plan)` (asyncio.gather 拉 P1 review_* + gold finance/products/channel/discount).
- Test (mock P1 + gold query 函数): plan 维度门控正确; gather 只拉 plan 内维度; P1 缺失 → review=None + plan.review False + next-action note.

### Task 5 — 合成 + LLM + fact-check 串联 (`synthesis_engine.py` 下半)
- `synthesize()` 全流程: cache → budget → factbook → InsightDimensionAnalyzer.analyze → call_chain LLM → FactReconciler → collect_charts → cache.put.
- system prompt 拼 orchestrator SYSTEM_PROMPT + 3 guard clause + 新 HONEST_LABEL_CLAUSE.
- **设 RedactionScope + `_llm_factory`** (§5.4, 镜像 orchestrator PR #355).
- Test (mock call_chain): grounded 数字来自 factbook; fact-check 串入; 出图按 plan 门控; budget exhausted → degraded; cache hit → 0 token.

### Task 6 — 出图组装 (`synthesis_engine._collect_charts`)
- 评价 pie (复用 P1) / 门店 bar / 渠道 pie / VIP bar / 时段 line, 按 plan 门控, 每图 title 含口径+诚实标注.
- Test: plan 维度 → 对应图; plan 不含 sales → 无渠道图; chart_config 是合法 ECharts option (type/series 齐全).

### Task 7 — API 端点 + SSE (`smartbi/api/synthesis.py` 或挂 chat.py)
- `POST /api/smartbi/synthesis/comprehensive` (非流) + `/comprehensive-stream` (SSE: meta/delta/charts/done, 复用 general-analysis-stream 事件格式).
- 在 `general-analysis-stream` 路由链早于 template 路由插 `match_comprehensive_synthesis` 分支 (路径 B).
- Test: 端点返回 answer+charts+factbook; SSE 事件序列正确; 路由分支命中综合问 / 不命中单维问 (路径 B 覆盖).

### Task 8 — Java 意图 + tool + GoldFinanceClient (路径 A)
- 新意图 `COMPREHENSIVE_SYNTHESIS` (RESTAURANT, 关键词 §4.1) 迁移 INSERT; Java tool (GoldBackedRestaurantTool 模式) → `GoldFinanceClient.fetchComprehensiveSynthesis` (转发 X-User-Role) → `/synthesis/comprehensive`.
- Test: 意图路由命中; 角色头转发 (RBAC, 金额非零); tool 返回可读 message + chartConfig.

### Task 9 — 数据库迁移 (smartbi + 意图)
- 意图迁移 (Java 侧 flyway `V*`): INSERT COMPREHENSIVE_SYNTHESIS 意图 + 关键词.
- 若 P2 需新表 (如 synthesis 专用 cache, 否则复用 narrative_cache 不需要) → smartbi `V<date>_NN__*.sql` + **GRANT INSERT/UPDATE/sequence 给 smartbi_user** (per `feedback_smartbi_table_grant_gap` HARD: 漏 grant → 写静默 0 行).
- 通过 `deploy-smartbi-python.sh` runner apply.

### Task 10 — 真实 PG E2E + headed Playwright (qhj prod RES_3101_009)
- 真库 (8086 prod) 跑 5+ 综合问题: "综合分析青花椒的评价和经营" / "VIP 和菜品门店的关系" / "各时段营收和评分关系" / "上海杭州两地表现对比" / "整体诊断经营".
- 断言: answer 数字全在 §2 FactBook 真值容差内 (无编造); 含诚实标注 ("口味标签非菜名"/小样本); 多张图渲染 (headed Playwright per `.claude/rules/playwright-headed-mode.md`, headless 禁); egress 审计 `sanitized=t` (§5.4); 单维问 NOT 误进综合引擎.
- per memory: 必须亲见真实 PASS=N FAIL=0 计数行 + prod 单独验, 绝不信"部署完成"日志.

### 并行工作建议
- **Subagent 并行**: Task 1/2/3 (factbook / router / reconciler 互独立, 纯函数) 可 3 subagent 并行.
- Task 4/5/6 依赖 1/2/3 → 顺序.
- Task 8 (Java) 与 Task 7 (Python) 可并行 (不同语言不同文件), 但 Task 8 测需 Task 7 端点在.
- Task 10 必须全部 merge + 从 main 部署 prod 后单跑 (per `feedback_worktree_main_only_deploy`: worktree off origin/main, prod 只从 main 部署).
- **多 Chat**: 不建议 (P2 集中在 synthesis_engine 单文件, 并发编辑风险, per concurrent-edit-safety).

---

## 7. 验收标准

1. "综合分析青花椒的评价和经营" → 一次返回: 评价 (4.79 星/好评 18139/差评 396 注小样本) + 经营 (gold 真实营收) + 交叉洞察 + 4 要素建议 + 多张图; 数字全可在 §2 FactBook 查到.
2. "VIP 和菜品、门店的关系" → 陈述 VIP 4.50 vs 非VIP 4.83 (真实信号) + 高频好评词 (标"口味标签非菜名") + VIP bar 图; 不编造因果.
3. FactReconciler 拦截编造数字 (注入 LLM mock 输出错数字 → 被加注纠正).
4. 单维问 ("评价怎么样" / "总营收") **NOT** 进综合引擎 (路由边界).
5. P1 评价空数据 → 降级只用经营 + next-action, 不 dead-end.
6. egress 审计 `sanitized=t` (脱敏 fire).
7. 真实 PG prod E2E PASS=N FAIL=0 (亲见计数行) + headed Playwright 图渲染截图.

---

## 附: 关键文件清单

| 文件 | 角色 | 新/改 |
|---|---|---|
| `smartbi/agent/factbook.py` | FactBook 数据结构 + 渲染 | 新 |
| `smartbi/agent/synthesis_router.py` | 综合问题路由判别 | 新 |
| `smartbi/agent/synthesis_engine.py` | 合成引擎主体 | 新 |
| `smartbi/services/llm_guard.py` | + FactReconciler | 改 (扩展) |
| `smartbi/api/synthesis.py` (或 chat.py) | API 端点 + SSE | 新/改 |
| `smartbi/agent/review_queries.py` | P1 评价聚合 (依赖) | P1 提供 |
| `smartbi/agent/orchestrator.py` | SYSTEM_PROMPT / `_fmt_money` 复用参考 | 复用 |
| `smartbi/services/insight_dimensions.py` | InsightDimensionAnalyzer 复用 | 复用 |
| `smartbi/gold/queries.py` | finance_summary/top_products/... 复用 | 复用 |
| `common/llm_router.py` | call_chain(SLOT.INSIGHTS) 复用 | 复用 |
| Java: 意图迁移 + tool + GoldFinanceClient | 路径 A | 新/改 |
